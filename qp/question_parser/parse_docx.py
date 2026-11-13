# -*- coding: utf-8 -*-
"""把「题目 + 答案 + 详解」型 docx 试卷解析成结构化题库。

设计目标：
  1. 零第三方依赖（标准库即可运行；WMF 公式转 PNG 时会调用 ImageMagick，可选）
  2. 一次写好，批量复用：python3 parse_docx.py --input-dir ./docs --out ./build
  3. 输出 HTML 片段 + 独立图片资源，公式优先 LaTeX，老式 MathType 公式降级为矢量图
  4. 模板变了只改 config.json，不改代码；低置信题目进复核队列，供 AI 兜底

输出：
  build/questions.jsonl          一题一行
  build/assets/                  按内容 hash 去重的图片
  build/report.csv               每题体检表
  build/待复核队列.jsonl          需要人工/AI 看的题目
  build/公式转LaTeX队列.jsonl     公式图片清单（可一次性交给多模态模型转 LaTeX）
  build/汇总.json
"""
import argparse
import csv
import hashlib
import html
import json
import os
import posixpath
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from omml_to_latex import convert_omml  # noqa: E402

NS = {
    'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
    'm': 'http://schemas.openxmlformats.org/officeDocument/2006/math',
    'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
    'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
    'v': 'urn:schemas-microsoft-com:vml',
    'wp': 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing',
    'o': 'urn:schemas-microsoft-com:office:office',
    'pic': 'http://schemas.openxmlformats.org/drawingml/2006/picture',
}
R_EMBED = '{' + NS['r'] + '}embed'
R_LINK = '{' + NS['r'] + '}link'
R_ID = '{' + NS['r'] + '}id'
PLACE = '\ue000'
PLACE_END = '\ue001'
PLACE_RE = re.compile(PLACE + r'(\d+)' + PLACE_END)

DEFAULT_CONFIG = {
    "section": r"^\s*([一二三四五六七八九十]+)\s*、\s*(单选题|多选题|选择题|填空题|解答题|实验题|计算题|证明题|综合题)\s*$",
    "question": r"^\s*(\d{1,3})\s*[.．、]\s*",
    "answer": r"^\s*[\[【(（]?\s*答\s*案\s*[\]】)）]?\s*[:：]?\s*(.*)$",
    "explanation": r"^\s*[\[【(（]?\s*(详\s*解|解\s*析|解\s*答|分\s*析)\s*[\]】)）]?\s*[:：]?\s*(.*)$",
    "option": r"([ABCD])\s*[.．、]",
    "noise": [
        r"^\s*试卷第\s*\d+\s*页",
        r"^\s*第?\s*\d+\s*页\s*[\(（]?\s*共",
        r"^\s*(学校|姓名|班级|考号|座号|得分|学号)\s*[:：]",
        r"^\s*$"
    ],
    "subject_keywords": {"物理": "物理", "数学": "数学", "化学": "化学", "生物": "生物"},
    "strict_sequence": True,
    "confidence_threshold": 0.85,
    "download_external": True,
    "formula_convert": True,
    "formula_density": 300,
    "webp": False
}


def q(prefix, tag):
    return '{%s}%s' % (NS[prefix], tag)


def local(tag):
    return tag.split('}')[-1]


def load_config(path):
    cfg = dict(DEFAULT_CONFIG)
    if path and Path(path).exists():
        with open(path, encoding='utf-8') as f:
            cfg.update(json.load(f))
    return cfg


class Seg:
    __slots__ = ('kind', 'value', 'meta')

    def __init__(self, kind, value, **meta):
        self.kind = kind
        self.value = value
        self.meta = meta


class Para:
    def __init__(self, segments):
        self.segments = segments

    @property
    def text(self):
        return ''.join(s.value for s in self.segments if s.kind == 'text')


def drop_prefix(para, n):
    """删掉段落开头 n 个字符（用于去掉「1．」「【答案】」这类标记）。"""
    segs = [Seg(s.kind, s.value, **s.meta) for s in para.segments]
    out, left = [], n
    for s in segs:
        if left <= 0:
            out.append(s)
            continue
        if s.kind == 'text':
            if len(s.value) <= left:
                left -= len(s.value)
                continue
            out.append(Seg('text', s.value[left:], **s.meta))
            left = 0
        else:
            out.append(s)
    return Para(out)


class AssetStore:
    """图片按内容 hash 落盘，跨文档全局去重。"""

    def __init__(self, build_dir, cfg):
        self.dir = Path(build_dir) / 'assets'
        self.dir.mkdir(parents=True, exist_ok=True)
        self.cache = {}
        self.cfg = cfg
        self.converter = None
        self.formula_hits = {}   # hash -> dict(源文件, 尺寸, 出现次数)
        if cfg.get('formula_convert', True):
            self.converter = shutil.which('magick') or shutil.which('convert')
        self.wmf_failed = 0

    def _convert_wmf(self, data, key):
        if not self.converter:
            return None
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / 'f.wmf'
            dst = Path(td) / 'f.png'
            src.write_bytes(data)
            cmd = [self.converter, '-density', str(self.cfg.get('formula_density', 300)),
                   str(src), '-background', 'white', '-alpha', 'remove', str(dst)]
            try:
                r = subprocess.run(cmd, capture_output=True, timeout=30)
                if r.returncode == 0 and dst.exists() and dst.stat().st_size > 0:
                    return dst.read_bytes()
            except Exception:
                pass
        self.wmf_failed += 1
        return None

    def store(self, data, ext, kind, meta=None):
        key = hashlib.sha256(data).hexdigest()[:20]
        if key in self.cache:
            if kind == 'formula' and key in self.formula_hits:
                self.formula_hits[key]['occurrences'] += 1
            return self.cache[key]
        ext = ext.lower()
        note = None
        if kind == 'formula' and ext in ('.wmf', '.emf'):
            cached = self.dir / (key + '.png')
            if cached.exists():          # 重复跑 / 批量跑时直接复用，不再启动转换器
                data, ext = cached.read_bytes(), '.png'
            else:
                png = self._convert_wmf(data, key)
                if png:
                    data, ext = png, '.png'
                else:
                    note = 'WMF 未转换，App 端需自行渲染或跑一次批量转换'
        if self.cfg.get('webp') and ext in ('.png', '.jpg', '.jpeg'):
            try:
                from PIL import Image
                import io
                im = Image.open(io.BytesIO(data)).convert('RGB')
                buf = io.BytesIO()
                im.save(buf, format='WEBP', quality=88)
                data, ext = buf.getvalue(), '.webp'
            except Exception:
                pass
        name = key + ext
        (self.dir / name).write_bytes(data)
        rel = 'assets/' + name
        self.cache[key] = rel
        if kind == 'formula':
            self.formula_hits[key] = {
                'src': rel, 'original_ext': ext if not note else '.wmf',
                'w_pt': (meta or {}).get('w_pt'),
                'h_pt': (meta or {}).get('h_pt'),
                'note': note, 'occurrences': 1,
            }
        return rel


class DocContext:
    def __init__(self, path, cfg, store):
        self.path = Path(path)
        self.cfg = cfg
        self.store = store
        self.zip = zipfile.ZipFile(path)
        self.rels = self._rels()
        self.warnings = []
        self.subject = self._guess_subject()

    def _rels(self):
        out = {}
        try:
            root = ET.fromstring(self.zip.read('word/_rels/document.xml.rels'))
        except KeyError:
            return out
        for rel in root:
            out[rel.get('Id')] = {
                'type': rel.get('Type', '').split('/')[-1],
                'target': rel.get('Target'),
                'mode': rel.get('TargetMode'),
            }
        return out

    def _guess_subject(self):
        try:
            root = ET.fromstring(self.zip.read('word/document.xml'))
            head = ''.join((t.text or '') for t in list(root.iter(q('w', 't')))[:40])
            for kw, name in self.cfg.get('subject_keywords', {}).items():
                if kw in head:
                    return name
        except Exception:
            pass
        return ''

    def fetch_image(self, rid):
        rel = self.rels.get(rid)
        if not rel:
            self.warnings.append('图片关系缺失: ' + str(rid))
            return None, None
        target = rel['target'] or ''
        if rel['mode'] == 'External' or target.startswith(('http://', 'https://')):
            if not self.cfg.get('download_external', True):
                return None, ('外链图未下载: ' + target)
            try:
                with urllib.request.urlopen(target, timeout=12) as resp:
                    data = resp.read()
                ext = os.path.splitext(target.split('?')[0])[1] or '.png'
                return (data, ext), None
            except Exception as e:
                return None, ('外链图下载失败: %s (%s)' % (target, type(e).__name__))
        member = posixpath.normpath(posixpath.join('word', target.lstrip('/')))
        if member.startswith('..') or member not in self.zip.namelist():
            return None, ('图片部件缺失: ' + member)
        ext = os.path.splitext(member)[1]
        return (self.zip.read(member), ext), None

    def add_image_seg(self, rid, shape_meta=None):
        got, err = self.fetch_image(rid)
        if not got:
            self.warnings.append(err or '图片读取失败')
            return Seg('text', '[图片缺失]')
        data, ext = got
        kind = 'formula' if ext.lower() in ('.wmf', '.emf') else 'figure'
        src = self.store.store(data, ext, kind, shape_meta)
        meta = dict(shape_meta or {})
        meta['src'] = src
        return Seg(kind, src, **meta)


def _shape_meta(obj):
    meta = {}
    for shape in obj.iter():
        if local(shape.tag) == 'shape':
            style = shape.get('style') or ''
            for key, attr in (('width', 'w_pt'), ('height', 'h_pt')):
                m = re.search(key + r'\s*:\s*([\d.]+)\s*pt', style)
                if m:
                    meta[attr] = float(m.group(1))
            break
    return meta


def _drawing_meta(drawing):
    meta = {}
    for ext in drawing.iter():
        if local(ext.tag) == 'extent':
            for key, attr in (('cx', 'w_px'), ('cy', 'h_px')):
                v = ext.get(key)
                if v and v.isdigit():
                    meta[attr] = round(int(v) / 9525)
            break
    return meta


def para_segments(p, ctx):
    segs = []

    def walk(el):
        tag = local(el.tag)
        if tag in ('pPr', 'rPr', 'bookmarkStart', 'bookmarkEnd', 'proofErr',
                   'sectPr', 'commentRangeStart', 'commentRangeEnd', 'lastRenderedPageBreak'):
            return
        if tag == 't':
            segs.append(Seg('text', el.text or ''))
            return
        if tag == 'tab':
            segs.append(Seg('text', ' '))
            return
        if tag == 'br':
            segs.append(Seg('break', '<br>'))
            return
        if tag in ('oMath', 'oMathPara'):
            latex, ok = convert_omml(el)
            segs.append(Seg('math', latex, ok=ok))
            if not ok:
                ctx.warnings.append('OMML 含未识别结构，公式已降级为文字')
            return
        if tag == 'drawing':
            meta = _drawing_meta(el)
            rid = None
            for sub in el.iter():
                if local(sub.tag) == 'blip':
                    rid = sub.get(R_EMBED) or sub.get(R_LINK)
                    break
            if rid:
                segs.append(ctx.add_image_seg(rid, meta))
            return
        if tag == 'object':
            meta = _shape_meta(el)
            rid = None
            for sub in el.iter():
                if local(sub.tag) == 'imagedata':
                    rid = sub.get(R_ID)
                    break
            if rid:
                segs.append(ctx.add_image_seg(rid, meta))
            return
        if tag == 'pict':
            rid = None
            for sub in el.iter():
                if local(sub.tag) == 'imagedata':
                    rid = sub.get(R_ID)
                    break
            if rid:
                segs.append(ctx.add_image_seg(rid))
            return
        if tag == 'delText':
            return
        for c in el:
            walk(c)

    for c in p:
        walk(c)
    return Para(segs)


def render_seg(s):
    if s.kind == 'text':
        return html.escape(s.value)
    if s.kind == 'break':
        return '<br>'
    if s.kind == 'math':
        return '<span class="q-math" data-latex="%s">\\(%s\\)</span>' % (
            html.escape(s.value, quote=True), html.escape(s.value))
    if s.kind == 'formula':
        h_pt = s.meta.get('h_pt') or 11
        em = round(h_pt / 10.5, 3)
        alt = html.escape(s.meta.get('latex') or '', quote=True)
        return ('<img class="q-formula" src="%s" alt="公式" data-latex="%s" '
                'data-h-pt="%s" style="height:%sem">') % (
                    html.escape(s.value, quote=True), alt, h_pt, em)
    if s.kind == 'figure':
        w = s.meta.get('w_px')
        style = 'max-width:100%;height:auto'
        return '<img class="q-figure-img" src="%s" alt="题图" style="%s"%s>' % (
            html.escape(s.value, quote=True), style,
            (' data-w="%d"' % w) if w else '')
    return ''


def render_paragraphs(paras):
    out = []
    for p in paras:
        inner = ''.join(render_seg(s) for s in p.segments)
        if not inner.strip():
            continue
        visible_text = ''.join(s.value for s in p.segments if s.kind == 'text').strip()
        if not visible_text and any(s.kind == 'figure' for s in p.segments):
            out.append('<figure class="q-figure">%s</figure>' % inner)
        else:
            out.append('<p>%s</p>' % inner)
    return ''.join(out)


def split_options(stem_paras, is_choice=True):
    """返回 (stem_html, options_html, warning)。

    优先用「段首 A．」定位选项区；找不到时退回全串扫描完整 A→B→C→D 序列。
    图片用私有区占位符参与切分，保证公式图落在正确选项里。
    解答/填空/计算题直接跳过选项拆分，避免产生「选项不足」的误报。
    """
    if not is_choice:
        return render_paragraphs(stem_paras), [], None
    opt_start = None
    for i, p in enumerate(stem_paras):
        if re.match(r'^\s*A\s*[.．、]', p.text):
            opt_start = i
            break
    warning = None
    if opt_start is None:
        opt_start = 0
        warning = '未找到段首 A 选项，使用全串兜底切分'
    stem_paras = list(stem_paras)
    head = stem_paras[:opt_start]
    tail = stem_paras[opt_start:]

    slots, buf = [], []
    for i, p in enumerate(tail):
        if i:
            buf.append('<br>')
        for s in p.segments:
            if s.kind == 'text':
                buf.append(html.escape(s.value))
            elif s.kind == 'break':
                buf.append('<br>')
            else:
                slots.append(render_seg(s))
                buf.append(PLACE + str(len(slots) - 1) + PLACE_END)
    S = ''.join(buf)

    expected = ['A', 'B', 'C', 'D']
    markers, idx = [], 0
    for m in re.finditer(r'([ABCD])\s*[.．、]', S):
        if idx < 4 and m.group(1) == expected[idx]:
            markers.append(m)
            idx += 1
        if idx == 4:
            break

    def restore(s):
        return PLACE_RE.sub(lambda mm: slots[int(mm.group(1))], s).strip()

    if len(markers) < 4:
        return render_paragraphs(stem_paras), [], (warning or '') + ' 选项不足 4 个，未拆分'

    # 兜底模式下，A 之前的内容仍是题干
    stem_extra = restore(S[:markers[0].start()]) if opt_start == 0 else ''
    stem_html = render_paragraphs(head)
    if stem_extra:
        stem_html += '<p>%s</p>' % stem_extra
    options = []
    for i, m in enumerate(markers):
        end = markers[i + 1].start() if i + 1 < 4 else len(S)
        piece = restore(S[m.start():end])
        piece = re.sub(r'^(<br>)+', '', piece)
        options.append(piece)
    return stem_html, options, warning


def has_content(paras):
    """判断一组段落是否有实质内容（纯公式图的答案也算有内容）。"""
    for p in paras:
        for s in p.segments:
            if s.kind == 'text' and s.value.strip():
                return True
            if s.kind in ('figure', 'formula', 'math'):
                return True
    return False


def collect_images(paras, where):
    out = []
    for p in paras:
        for s in p.segments:
            if s.kind in ('figure', 'formula'):
                out.append({
                    'src': s.value, 'kind': s.kind, 'in': where,
                    'w_pt': s.meta.get('w_pt'), 'h_pt': s.meta.get('h_pt'),
                    'w_px': s.meta.get('w_px'), 'h_px': s.meta.get('h_px'),
                })
            elif s.kind == 'math':
                out.append({'src': None, 'kind': 'math', 'in': where,
                            'latex': s.value, 'ok': s.meta.get('ok', True)})
    return out


def guess_type(section, options, answer_text):
    plain = re.sub(r'<[^>]+>', '', answer_text).strip()
    if options:
        if re.fullmatch(r'[ABCD](?:\s*[、,，]?\s*[ABCD])*', plain):
            return 'multi_choice' if len(re.findall(r'[ABCD]', plain)) > 1 else 'single_choice'
        return 'choice'
    if '解答' in section or '计算' in section or '证明' in section:
        return 'solution'
    if '填空' in section:
        return 'fill_blank'
    return 'short_answer'


def parse_document(path, cfg, store):
    ctx = DocContext(path, cfg, store)
    root = ET.fromstring(ctx.zip.read('word/document.xml'))
    body = root.find(q('w', 'body'))
    paras = [para_segments(p, ctx) for p in body.iter(q('w', 'p'))]

    section_re = re.compile(cfg['section'])
    q_re = re.compile(cfg['question'])
    a_re = re.compile(cfg['answer'])
    e_re = re.compile(cfg['explanation'])
    noise_res = [re.compile(x) for x in cfg['noise']]
    doc_key = hashlib.sha1(str(ctx.path.name).encode('utf-8')).hexdigest()[:8]
    questions = []
    section = ''
    cur = None
    mode = 'stem'
    stem_p, ans_p, exp_p = [], [], []
    last_number = 0

    def flush():
        nonlocal cur, stem_p, ans_p, exp_p
        if cur is None:
            return
        is_choice = not any(k in cur['section'] for k in ('解答', '计算', '证明', '填空', '实验'))
        stem_html, options, w = split_options(stem_p, is_choice)
        if w:
            cur['warnings'].append(w.strip())
        answer_html = render_paragraphs(ans_p)
        explanation_html = render_paragraphs(exp_p)
        answer_ok = has_content(ans_p)
        answer_plain = re.sub(r'<[^>]+>', '', answer_html).strip()
        images = (collect_images(stem_p, 'stem') + collect_images(ans_p, 'answer') +
                  collect_images(exp_p, 'explanation'))
        kinds = {i['kind'] for i in images}
        math_segs = [s for p in stem_p + ans_p + exp_p for s in p.segments if s.kind == 'math']
        if math_segs and 'formula' in kinds:
            formula_mode = 'mixed'
        elif math_segs:
            formula_mode = 'latex'
        elif 'formula' in kinds:
            formula_mode = 'image'
        else:
            formula_mode = 'none'
        if not answer_ok:
            m = re.search(r'故选\s*([ABCD])', re.sub(r'<[^>]+>', '', explanation_html))
            if m:
                answer_html = '<p>%s</p>' % m.group(1)
                answer_plain = m.group(1)
                answer_ok = True
                cur['warnings'].append('答案由「故选」推断')
        conf = 1.0
        if formula_mode in ('image', 'mixed'):
            conf -= 0.10
        if not answer_ok:
            conf -= 0.40
            cur['warnings'].append('答案缺失')
        if len(options) == 0 and '选择' in cur['section']:
            conf -= 0.20
            cur['warnings'].append('选择题未拆出选项')
        if any(s.kind == 'math' and not s.meta.get('ok', True)
               for p in stem_p + ans_p + exp_p for s in p.segments):
            conf -= 0.15
            cur['warnings'].append('公式结构未完全识别')
        if '图片缺失' in ' '.join(ctx.warnings) or '下载失败' in ' '.join(ctx.warnings):
            conf -= 0.10
        conf = max(0.0, round(conf, 2))
        cur.update({
            'type': guess_type(cur['section'], options, answer_html),
            'stem_html': stem_html,
            'options_html': options,
            'answer_html': answer_html,
            'explanation_html': explanation_html,
            'images': images,
            'formula_mode': formula_mode,
            'confidence': conf,
        })
        questions.append(cur)
        stem_p, ans_p, exp_p = [], [], []
        cur = None

    for para in paras:
        raw = para.text
        text = raw.strip()
        has_media = any(s.kind in ('figure', 'formula', 'math') for s in para.segments)
        # 纯空白段落丢弃；但「只有一张题图」的段落必须保留
        if not text and not has_media:
            continue
        if text and any(r.match(text) for r in noise_res):
            continue
        m = section_re.match(raw)
        if m:
            section = m.group(0).strip()
            continue
        m = q_re.match(raw)
        n = int(m.group(1)) if m else None
        if m and (cur is None or
                  (not cfg.get('strict_sequence', True)) or
                  n == last_number + 1):
            flush()
            mode = 'stem'
            cur = {
                'id': '%s_q%03d' % (doc_key, n),
                'source_doc': ctx.path.name,
                'subject': ctx.subject,
                'section': section,
                'number': n,
                'warnings': [],
            }
            last_number = n
            stem_p.append(drop_prefix(para, m.end()))
            continue
        if cur is None:
            continue
        m = a_re.match(raw)
        if m and mode == 'stem':
            mode = 'answer'
            ans_p.append(drop_prefix(para, m.start(1)))
            continue
        m = e_re.match(raw)
        if m and mode in ('stem', 'answer'):
            mode = 'explanation'
            exp_p.append(drop_prefix(para, m.start(2)))
            continue
        if mode == 'stem':
            stem_p.append(para)
        elif mode == 'answer':
            ans_p.append(para)
        else:
            exp_p.append(para)
    flush()

    nums = [x['number'] for x in questions]
    gaps = [b for a, b in zip(nums, nums[1:]) if b != a + 1]
    for x in questions:
        if gaps:
            x['warnings'].append('题号不连续')
            x['confidence'] = max(0.0, round(x['confidence'] - 0.15, 2))
        for w in ctx.warnings:
            if w not in x['warnings']:
                x['warnings'].append(w)
    return questions, {
        'source_doc': ctx.path.name,
        'questions': len(questions),
        'numbers': nums,
        'gaps': gaps,
        'warnings': ctx.warnings,
    }


def write_outputs(build_dir, all_questions, doc_stats, store, cfg):
    build = Path(build_dir)
    build.mkdir(parents=True, exist_ok=True)
    with open(build / 'questions.jsonl', 'w', encoding='utf-8') as f:
        for item in all_questions:
            f.write(json.dumps(item, ensure_ascii=False) + '\n')
    with open(build / 'report.csv', 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(['source_doc', 'number', 'id', 'section', 'type', 'answer_ok',
                    'options', 'figures', 'formula_images', 'omml_math',
                    'formula_mode', 'confidence', 'warnings'])
        for x in all_questions:
            imgs = x.get('images', [])
            w.writerow([
                x['source_doc'], x['number'], x['id'], x['section'], x['type'],
                bool(re.sub(r'<[^>]+>', '', x['answer_html']).strip()
                     or '<img' in x['answer_html'] or 'q-math' in x['answer_html']),
                len(x['options_html']),
                sum(1 for i in imgs if i['kind'] == 'figure'),
                sum(1 for i in imgs if i['kind'] == 'formula'),
                sum(1 for i in imgs if i['kind'] == 'math'),
                x['formula_mode'], x['confidence'], ' | '.join(x['warnings']),
            ])
    review = [x for x in all_questions
              if x['confidence'] < cfg.get('confidence_threshold', 0.85) or x['warnings']]
    with open(build / '待复核队列.jsonl', 'w', encoding='utf-8') as f:
        for x in review:
            f.write(json.dumps({
                'id': x['id'], 'confidence': x['confidence'],
                'warnings': x['warnings'],
                'stem_plain': re.sub(r'<[^>]+>', ' ', x['stem_html'])[:300],
                'answer_plain': re.sub(r'<[^>]+>', ' ', x['answer_html'])[:200],
            }, ensure_ascii=False) + '\n')
    with open(build / '公式转LaTeX队列.jsonl', 'w', encoding='utf-8') as f:
        for item in store.formula_hits.values():
            f.write(json.dumps(item, ensure_ascii=False) + '\n')
    total_assets = sum(p.stat().st_size for p in (build / 'assets').iterdir())
    summary = {
        'documents': len(doc_stats),
        'questions': len(all_questions),
        'assets_files': len(store.cache),
        'assets_bytes': total_assets,
        'formula_images_unique': len(store.formula_hits),
        'wmf_convert_failed': store.wmf_failed,
        'low_confidence': len(review),
        'documents_detail': doc_stats,
    }
    with open(build / '汇总.json', 'w', encoding='utf-8') as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    return summary


def main():
    ap = argparse.ArgumentParser(description='docx 试卷 -> 结构化题库')
    ap.add_argument('--input', help='单个 docx')
    ap.add_argument('--input-dir', help='批量目录（递归找 .docx）')
    ap.add_argument('--out', default='build', help='输出目录')
    ap.add_argument('--config', default='config.json', help='规则配置')
    ap.add_argument('--no-formula-convert', action='store_true', help='不把 WMF 公式转 PNG')
    ap.add_argument('--webp', action='store_true', help='把位图压成 WebP（需 Pillow）')
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.no_formula_convert:
        cfg['formula_convert'] = False
    if args.webp:
        cfg['webp'] = True
    files = []
    if args.input:
        files.append(Path(args.input))
    if args.input_dir:
        files.extend(sorted(Path(args.input_dir).rglob('*.docx')))
    files = [p for p in files if p.is_file() and not p.name.startswith('~$')]
    if not files:
        ap.error('请用 --input 或 --input-dir 指定 docx')

    build = Path(args.out)
    store = AssetStore(build, cfg)
    all_questions, stats = [], []
    for p in files:
        try:
            qs, st = parse_document(p, cfg, store)
            all_questions.extend(qs)
            stats.append(st)
            print('[OK] %s -> %d 题' % (p.name, len(qs)))
        except Exception as e:
            stats.append({'source_doc': p.name, 'error': '%s: %s' % (type(e).__name__, e)})
            print('[FAIL] %s -> %s: %s' % (p.name, type(e).__name__, e), file=sys.stderr)
    summary = write_outputs(build, all_questions, stats, store, cfg)
    print('\n汇总:', json.dumps({k: v for k, v in summary.items() if k != 'documents_detail'},
                               ensure_ascii=False))
    print('输出目录:', build.resolve())


if __name__ == '__main__':
    main()
