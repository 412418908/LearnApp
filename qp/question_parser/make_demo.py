# -*- coding: utf-8 -*-
"""生成自包含的《题目预览.html》：内嵌若干真实题目 + 图片 data URI，双击即可看效果。

用法：python3 make_demo.py --jsonl build/questions.jsonl --out 题目预览.html --limit 6
正式 App 不需要这个文件，它只是验证「一页一题 / 点答案 / 可复制 / 公式图」的交互。
"""
import argparse
import base64
import io
import json
import mimetypes
import re
from pathlib import Path

SRC_RE = re.compile(r'src="(assets/[^"]+)"')


def to_data_uri(rel, assets_dir):
    p = assets_dir / Path(rel).name
    if not p.exists():
        return rel
    raw = p.read_bytes()
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(raw))
        if im.width > 1000:
            h = round(im.height * 1000 / im.width)
            im = im.resize((1000, h), Image.LANCZOS)
        if im.mode not in ('RGB', 'RGBA'):
            im = im.convert('RGBA')
        buf = io.BytesIO()
        im.save(buf, format='WEBP', quality=82, method=4)
        return 'data:image/webp;base64,' + base64.b64encode(buf.getvalue()).decode()
    except Exception:
        mime = mimetypes.guess_type(p.name)[0] or 'application/octet-stream'
        return 'data:%s;base64,%s' % (mime, base64.b64encode(raw).decode())


def pick(items, limit):
    chosen, seen = [], set()
    for x in items[:limit]:
        chosen.append(x)
        seen.add(x['id'])
    for x in items:
        if len(chosen) >= limit:
            break
        if x['type'] in ('solution', 'short_answer') and x['id'] not in seen:
            chosen.append(x)
            seen.add(x['id'])
    return chosen[:limit]


TEMPLATE = r'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>题目预览 · 结构化题库 Demo</title>
<style>
  :root{--bg:#f5f6f8;--card:#fff;--ink:#1c2026;--sub:#6b7280;--line:#e5e7eb;--brand:#2563eb;--ok:#059669}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.75 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif}
  .wrap{max-width:720px;margin:0 auto;padding:16px 14px 60px}
  header{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:14px}
  header h1{font-size:17px;margin:0;font-weight:650}
  .badge{font-size:12px;color:var(--sub);background:#fff;border:1px solid var(--line);padding:3px 9px;border-radius:20px}
  .bar{height:4px;background:var(--line);border-radius:4px;overflow:hidden;margin-bottom:14px}
  .bar>i{display:block;height:100%;background:var(--brand);transition:width .25s}
  .card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px 18px 22px;box-shadow:0 2px 10px rgba(20,30,60,.04)}
  .meta{display:flex;gap:8px;flex-wrap:wrap;font-size:12px;color:var(--sub);margin-bottom:12px}
  .meta span{background:#f3f4f6;border-radius:6px;padding:2px 8px}
  .q-body{font-size:16.5px}
  .q-body p{margin:0 0 10px}
  .q-body figure{margin:12px 0;text-align:center}
  .q-figure-img{max-width:100%;height:auto;border-radius:8px}
  .q-formula{vertical-align:-.18em;max-width:100%;cursor:pointer;border-bottom:1px dashed transparent}
  .q-formula:hover{border-bottom-color:var(--brand)}
  .q-math{font-family:"SFMono-Regular",Consolas,monospace;background:#eef2ff;color:#3730a3;padding:1px 5px;border-radius:4px;font-size:.92em}
  .opt{padding:10px 12px;margin:8px 0;background:#fafafa;border:1px solid var(--line);border-radius:10px}
  .opt:hover{background:#f0f6ff}
  .actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:18px}
  button{font:inherit;font-size:14px;padding:9px 15px;border-radius:9px;border:1px solid var(--line);background:#fff;color:var(--ink);cursor:pointer}
  button.primary{background:var(--brand);border-color:var(--brand);color:#fff}
  button:active{transform:translateY(1px)}
  .panel{display:none;margin-top:14px;border-radius:10px;padding:13px 15px;font-size:15px}
  .panel.show{display:block;animation:fade .18s ease}
  @keyframes fade{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:none}}
  .answer{background:#ecfdf5;border:1px solid #a7f3d0}
  .expl{background:#f8fafc;border:1px solid var(--line)}
  .panel h4{margin:0 0 8px;font-size:13px;color:var(--sub);font-weight:600;letter-spacing:.04em}
  .nav{display:flex;justify-content:space-between;margin-top:16px}
  .tip{font-size:12px;color:var(--sub);margin-top:18px;line-height:1.7}
  .toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#111827;color:#fff;font-size:13px;padding:9px 16px;border-radius:20px;opacity:0;pointer-events:none;transition:opacity .2s}
  .toast.on{opacity:.92}
</style>
</head>
<body>
<div class="wrap">
  <header><h1>题目预览</h1><span class="badge" id="badge"></span></header>
  <div class="bar"><i id="bar"></i></div>
  <div class="card">
    <div class="meta" id="meta"></div>
    <div class="q-body" id="stem"></div>
    <div id="opts"></div>
    <div class="actions">
      <button class="primary" id="btnAnswer">显示答案</button>
      <button id="btnExpl">显示详解</button>
      <button id="btnCopy">复制题干</button>
    </div>
    <div class="panel answer" id="answer"><h4>答案</h4><div id="answerBody"></div></div>
    <div class="panel expl" id="expl"><h4>详解</h4><div id="explBody"></div></div>
    <div class="nav">
      <button id="prev">← 上一题</button>
      <button id="next">下一题 →</button>
    </div>
  </div>
  <div class="tip">
    这是本地生成的静态预览：文字可选中复制，公式图为内嵌的矢量转位图（点公式可复制 LaTeX，未转换时提示）。
    正式 App 用 WebView 渲染同样的 HTML 片段 + 本地 KaTeX + SQLite；OMML 公式会直接以 LaTeX 文本存储，可无损复制。
  </div>
</div>
<div class="toast" id="toast"></div>
<script>
const DATA = __DATA__;
let i = 0;
const $ = id => document.getElementById(id);
function toast(msg){const t=$('toast');t.textContent=msg;t.classList.add('on');clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove('on'),1600)}
function render(){
  const q = DATA[i];
  $('badge').textContent = (i+1) + ' / ' + DATA.length;
  $('bar').style.width = ((i+1)/DATA.length*100) + '%';
  $('meta').innerHTML = ['第 '+q.number+' 题', q.section||'题库', q.subject||'', q.type,
      '置信度 '+q.confidence].filter(Boolean).map(s=>'<span>'+s+'</span>').join('');
  $('stem').innerHTML = q.stem_html;
  $('opts').innerHTML = (q.options_html||[]).map(o=>'<div class="opt">'+o+'</div>').join('');
  $('answerBody').innerHTML = q.answer_html;
  $('explBody').innerHTML = q.explanation_html;
  $('answer').classList.remove('show'); $('expl').classList.remove('show');
  $('btnAnswer').textContent='显示答案'; $('btnExpl').textContent='显示详解';
  $('prev').disabled = i===0; $('next').disabled = i===DATA.length-1;
  window.scrollTo({top:0,behavior:'smooth'});
}
function plain(html){const d=document.createElement('div');d.innerHTML=html;return d.innerText.trim()}
$('btnAnswer').onclick=()=>{const p=$('answer');p.classList.toggle('show');$('btnAnswer').textContent=p.classList.contains('show')?'收起答案':'显示答案'};
$('btnExpl').onclick=()=>{const p=$('expl');p.classList.toggle('show');$('btnExpl').textContent=p.classList.contains('show')?'收起详解':'显示详解'};
$('btnCopy').onclick=()=>{const q=DATA[i];const t=plain(q.stem_html)+'\n'+(q.options_html||[]).map(plain).join('\n');
  navigator.clipboard?.writeText(t).then(()=>toast('题干+选项已复制')).catch(()=>toast('请长按选中文字复制'))};
$('prev').onclick=()=>{if(i>0){i--;render()}};
$('next').onclick=()=>{if(i<DATA.length-1){i++;render()}};
document.addEventListener('click',e=>{
  if(e.target.matches('.q-formula')){
    const l=e.target.getAttribute('data-latex');
    if(l&&l!=='公式'){navigator.clipboard?.writeText('$'+l+'$').then(()=>toast('公式 LaTeX 已复制'));}
    else toast('该公式是老式 Word 公式图，尚未转 LaTeX');
  }
});
render();
</script>
</body>
</html>
'''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jsonl', default='build/questions.jsonl')
    ap.add_argument('--out', default='题目预览.html')
    ap.add_argument('--limit', type=int, default=6)
    args = ap.parse_args()
    items = [json.loads(l) for l in open(args.jsonl, encoding='utf-8') if l.strip()]
    chosen = pick(items, args.limit)
    assets = Path(args.jsonl).parent / 'assets'

    def embed(h):
        return SRC_RE.sub(lambda m: 'src="%s"' % to_data_uri(m.group(1), assets), h)

    out = []
    for q in chosen:
        d = dict(q)
        d['stem_html'] = embed(q['stem_html'])
        d['options_html'] = [embed(x) for x in q['options_html']]
        d['answer_html'] = embed(q['answer_html'])
        d['explanation_html'] = embed(q['explanation_html'])
        out.append(d)
    payload = json.dumps(out, ensure_ascii=False).replace('</', '<\\/')
    html_out = TEMPLATE.replace('__DATA__', payload)
    Path(args.out).write_text(html_out, encoding='utf-8')
    print('已生成 %s（%d 题，%.1f KB）' %
          (Path(args.out).resolve(), len(out), Path(args.out).stat().st_size / 1024))


if __name__ == '__main__':
    main()
