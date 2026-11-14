# -*- coding: utf-8 -*-
"""OMML(Word 自带公式) -> LaTeX 轻量转换器。

只依赖标准库。覆盖中学数理化常见结构：分数、上下标、根号、括号、
求和/积分、函数名、上划线/帽子、对齐公式。遇到无法识别的结构时，
降级为「收集其中文字」并把 ok 置 False，由上层标记低置信度。

新式 docx（Word 公式编辑器）走这里；老式 MathType OLE 公式不走这里，
见 parse_docx.py 里的公式图处理。
"""
import re
from xml.etree import ElementTree as ET

M = '{http://schemas.openxmlformats.org/officeDocument/2006/math}'

GREEK = {
    'α': r'\alpha', 'β': r'\beta', 'γ': r'\gamma', 'δ': r'\delta',
    'ε': r'\varepsilon', 'ζ': r'\zeta', 'η': r'\eta', 'θ': r'\theta',
    'ι': r'\iota', 'κ': r'\kappa', 'λ': r'\lambda', 'μ': r'\mu',
    'ν': r'\nu', 'ξ': r'\xi', 'π': r'\pi', 'ρ': r'\rho',
    'σ': r'\sigma', 'τ': r'\tau', 'υ': r'\upsilon', 'φ': r'\varphi',
    'χ': r'\chi', 'ψ': r'\psi', 'ω': r'\omega',
    'Γ': r'\Gamma', 'Δ': r'\Delta', 'Θ': r'\Theta', 'Λ': r'\Lambda',
    'Ξ': r'\Xi', 'Π': r'\Pi', 'Σ': r'\Sigma', 'Φ': r'\Phi',
    'Ψ': r'\Psi', 'Ω': r'\Omega',
}
SYMBOLS = {
    '±': r'\pm', '×': r'\times', '÷': r'\div', '·': r'\cdot',
    '≤': r'\le', '≥': r'\ge', '≠': r'\ne', '≈': r'\approx',
    '∞': r'\infty', '→': r'\to', '←': r'\leftarrow', '⇒': r'\Rightarrow',
    '∈': r'\in', '∉': r'\notin', '⊂': r'\subset', '∪': r'\cup',
    '∩': r'\cap', '∅': r'\varnothing', '∠': r'\angle', '°': r'^{\circ}',
    '⊥': r'\perp', '∥': r'\parallel', '∑': r'\sum', '∫': r'\int',
    '²': r'^{2}', '³': r'^{3}', '₁': r'_{1}', '₂': r'_{2}',
    '₀': r'_{0}',
}
ESCAPE = {'\\': r'\backslash', '{': r'\{', '}': r'\}', '^': r'\^{}',
          '_': r'\_', '$': r'\$', '%': r'\%', '&': r'\&', '#': r'\#'}


def _local(tag):
    return tag.split('}')[-1]


def _text_of(el):
    return ''.join((t.text or '') for t in el.iter(M + 't'))


def latex_text(s):
    out = []
    for ch in s:
        if ch in ESCAPE:
            out.append(ESCAPE[ch])
        elif ch in GREEK:
            out.append(GREEK[ch] + ' ')
        elif ch in SYMBOLS:
            out.append(SYMBOLS[ch])
        else:
            out.append(ch)
    return ''.join(out)


class _Converter:
    def __init__(self):
        self.ok = True

    def children(self, el, skip_props=True):
        parts = []
        for c in el:
            if skip_props and _local(c.tag).endswith('Pr'):
                continue
            parts.append(self.conv(c))
        return ''.join(parts)

    def first(self, el, name, default=''):
        for c in el:
            if c.tag == M + name:
                return self.children(c)
        return default

    def conv(self, el):
        t = el.tag
        if t in (M + 'oMath', M + 'oMathPara', M + 'e', M + 'num', M + 'den',
                 M + 'deg', M + 'sub', M + 'sup', M + 'fName', M + 'limLow',
                 M + 'limUpp', M + 'box', M + 'groupChr', M + 'borderBox',
                 M + 'phantom', M + 'eqArr', M + 'm'):
            return self.children(el)
        if t == M + 'r':
            return latex_text(_text_of(el))
        if t == M + 'f':
            return r'\frac{%s}{%s}' % (self.first(el, 'num'), self.first(el, 'den'))
        if t == M + 'sSub':
            return '{%s}_{%s}' % (self.first(el, 'e'), self.first(el, 'sub'))
        if t == M + 'sSup':
            return '{%s}^{%s}' % (self.first(el, 'e'), self.first(el, 'sup'))
        if t == M + 'sSubSup':
            return '{%s}_{%s}^{%s}' % (self.first(el, 'e'), self.first(el, 'sub'),
                                       self.first(el, 'sup'))
        if t == M + 'rad':
            deg = ''
            for c in el:
                if c.tag == M + 'deg':
                    deg = self.children(c)
                    break
            body = self.first(el, 'e')
            return r'\sqrt[%s]{%s}' % (deg, body) if deg.strip() else r'\sqrt{%s}' % body
        if t == M + 'd':
            beg, end, sep = '(', ')', ''
            for c in el:
                if c.tag == M + 'dPr':
                    for p in c:
                        lt = _local(p.tag)
                        v = p.get(M + 'val')
                        if v is None:
                            continue
                        if lt == 'begChr':
                            beg = v
                        elif lt == 'endChr':
                            end = v
                        elif lt == 'sepChr':
                            sep = v
            body = self.children(el)
            if sep:
                body = body.replace(sep, ' \\, ')
            lmap = {'(': r'\left(', ')': r'\right)', '[': r'\left[',
                    ']': r'\right]', '{': r'\left\{', '}': r'\right\}',
                    '|': r'\left|'}
            rmap = {'(': r'\right)', ')': r'\left.', '[': r'\right]',
                    ']': r'\left.', '{': r'\right\}', '}': r'\left.',
                    '|': r'\right|'}
            return lmap.get(beg, beg) + body + rmap.get(end, end)
        if t == M + 'nary':
            op = r'\sum'
            for c in el:
                if c.tag == M + 'naryPr':
                    for p in c:
                        if p.tag == M + 'chr' and p.get(M + 'val'):
                            op = SYMBOLS.get(p.get(M + 'val'), r'\sum')
            sub = self.first(el, 'sub')
            sup = self.first(el, 'sup')
            body = self.first(el, 'e')
            return op + ('_{%s}' % sub if sub else '') + ('^{%s}' % sup if sup else '') + body
        if t == M + 'func':
            name = self.first(el, 'fName').strip()
            body = self.first(el, 'e')
            known = {'sin', 'cos', 'tan', 'cot', 'sec', 'csc', 'log', 'ln',
                     'lim', 'max', 'min', 'exp', 'arcsin', 'arccos', 'arctan'}
            name = ('\\' + name) if name in known else latex_text(name)
            return name + '{%s}' % body
        if t == M + 'acc':
            # 帽子 / 向量等，默认上划线
            return r'\overline{%s}' % self.first(el, 'e')
        if t == M + 'bar':
            return r'\overline{%s}' % self.first(el, 'e')
        if t == M + 'eqArr':
            rows = [self.children(c) for c in el if c.tag == M + 'e']
            return r'\begin{aligned}' + r' \\ '.join(rows) + r'\end{aligned}'
        if t == M + 'm':
            rows = []
            for mr in el:
                if mr.tag == M + 'mr':
                    cells = [self.children(c) for c in mr if c.tag == M + 'e']
                    rows.append(' & '.join(cells))
            return r'\begin{matrix}' + r' \\ '.join(rows) + r'\end{matrix}'
        if t == M + 'sPre':
            return self.children(el)
        # 未识别结构：保留文字，标记降级
        self.ok = False
        return latex_text(_text_of(el))


def convert_omml(el):
    """返回 (latex:str, ok:bool)。ok=False 表示有结构没认出来，建议人工/AI 复核。"""
    c = _Converter()
    latex = c.conv(el)
    latex = re.sub(r'\s+', ' ', latex).strip()
    return latex, c.ok


if __name__ == '__main__':
    demo = '''<m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
      <m:f><m:num><m:r><m:t>1</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f>
      <m:r><m:t>mg+</m:t></m:r><m:sSup><m:e><m:r><m:t>v</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup>
    </m:oMath>'''
    print(convert_omml(ET.fromstring(demo)))
