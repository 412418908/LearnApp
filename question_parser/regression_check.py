# -*- coding: utf-8 -*-
"""两卷回归断言：改完解析脚本先跑这个，任何一条不过都算回归。

用法：python3 regression_check.py
基于合并批量产物 build/，不需要单卷输出目录。
换成自己的样本时，改下面的 source_doc 分组条件与期望题数即可。
"""
import json
import re
import sqlite3
from pathlib import Path


def load(p):
    return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]


def main():
    allq = load('build/questions.jsonl')
    phy = [q for q in allq if '2026年11月9日' in q['source_doc']]
    bio = [q for q in allq if 'shengwu' in q['source_doc']]

    assert len(allq) == 108, ('合并题数不对', len(allq))
    assert len(phy) == 14 and [q['number'] for q in phy] == list(range(1, 15))
    assert len(bio) == 94 and [q['number'] for q in bio] == list(range(1, 95))
    assert len({q['id'] for q in allq}) == 108, '跨文档 id 撞了'

    FIELDS = {'id', 'source_doc', 'subject', 'section', 'number', 'type',
              'stem_html', 'options_html', 'answer_html', 'explanation_html',
              'images', 'formula_mode', 'confidence', 'warnings'}
    for q in allq:
        assert set(q) == FIELDS, ('字段变了', q['id'], set(q) ^ FIELDS)

    for q in allq:
        assert (re.sub(r'<[^>]+>', '', q['answer_html']).strip()
                or '<img' in q['answer_html']), ('答案缺失', q['id'])
        for img in q['images']:
            if img['src']:
                assert (Path('build') / img['src']).exists(), ('图片悬空', img['src'])

    by_num = {q['number']: q for q in bio}
    assert sum(1 for q in bio if q['type'] == 'multi_choice') == 13, '多选题型判定变了'
    assert sum(1 for q in bio if q['type'] == 'solution') == 11, '解答题型判定变了'
    assert 'q-table' in by_num[76]['stem_html'], '76 题表格丢了'
    assert by_num[71]['answer_html'].strip() == '<p>ABD</p>', '多选答案回填错了'
    assert ('(5)' in by_num[94]['answer_html']
            or '（5）' in by_num[94]['answer_html']), '解答题多小题答案截断'

    review = sum(1 for _ in open('build/待复核队列.jsonl', encoding='utf-8'))
    assert review == 1, ('复核队列异常膨胀', review)

    con = sqlite3.connect('build/questions.db')
    assert con.execute('select count(*) from questions').fetchone()[0] == 108
    con.close()
    print('两卷回归通过：%d 题（物理 %d / 生物 %d），字段契约、答案、图片、表格、题型全部一致'
          % (len(allq), len(phy), len(bio)))


if __name__ == '__main__':
    main()
