# -*- coding: utf-8 -*-
"""questions.jsonl -> SQLite，App 端直接按 id 取题 / 全文检索。

用法：python3 build_db.py --jsonl build/questions.jsonl --db build/questions.db
"""
import argparse
import json
import os
import re
import sqlite3
import tempfile
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS questions (
    id              TEXT PRIMARY KEY,
    source_doc      TEXT NOT NULL,
    subject         TEXT,
    section         TEXT,
    number          INTEGER,
    type            TEXT,
    stem_html       TEXT,
    options_json    TEXT,
    answer_html     TEXT,
    explanation_html TEXT,
    images_json     TEXT,
    formula_mode    TEXT,
    confidence      REAL,
    warnings_json   TEXT
);
CREATE INDEX IF NOT EXISTS idx_q_source ON questions(source_doc, number);
CREATE INDEX IF NOT EXISTS idx_q_type   ON questions(type);
CREATE INDEX IF NOT EXISTS idx_q_conf   ON questions(confidence);
"""


def plain(h):
    h = re.sub(r'<img[^>]*data-latex="([^"]*)"[^>]*>', lambda m: m.group(1) or ' [公式] ', h)
    h = re.sub(r'<img[^>]*>', ' [图] ', h)
    h = re.sub(r'<[^>]+>', ' ', h)
    return re.sub(r'\s+', ' ', h).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jsonl', default='build/questions.jsonl')
    ap.add_argument('--db', default='build/questions.db')
    args = ap.parse_args()
    db_path = Path(args.db).resolve()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    # 部分容器/网络盘的 /tmp 不可写，SQLite 连临时文件都建不了会直接报 disk I/O error。
    # 把临时目录指到产物目录，并用 URI 方式打开，绕开默认 journal 探测。
    os.environ['TMPDIR'] = str(db_path.parent)
    tempfile.tempdir = str(db_path.parent)
    try:
        con = sqlite3.connect(db_path.as_uri() + '?mode=rwc', uri=True)
    except sqlite3.Error:
        con = sqlite3.connect(str(db_path))
    # 某些容器/网络盘不支持 SQLite 的回滚日志文件，关闭磁盘 journal 后即可正常落库
    for pragma in ('PRAGMA journal_mode=MEMORY', 'PRAGMA locking_mode=EXCLUSIVE',
                   'PRAGMA synchronous=OFF', 'PRAGMA temp_store=MEMORY'):
        try:
            con.execute(pragma)
        except sqlite3.Error:
            pass
    con.executescript(SCHEMA)
    rows = []
    with open(args.jsonl, encoding='utf-8') as f:
        for line in f:
            if not line.strip():
                continue
            x = json.loads(line)
            rows.append((
                x['id'], x['source_doc'], x['subject'], x['section'], x['number'],
                x['type'], x['stem_html'], json.dumps(x['options_html'], ensure_ascii=False),
                x['answer_html'], x['explanation_html'],
                json.dumps(x['images'], ensure_ascii=False),
                x['formula_mode'], x['confidence'],
                json.dumps(x['warnings'], ensure_ascii=False),
            ))
    con.executemany('INSERT OR REPLACE INTO questions VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)', rows)
    # 全文检索（FTS5 不可用时静默跳过）
    try:
        con.execute('CREATE VIRTUAL TABLE IF NOT EXISTS questions_fts USING fts5(id, body)')
        con.execute('DELETE FROM questions_fts')
        con.executemany(
            'INSERT INTO questions_fts(id, body) VALUES (?,?)',
            [(x[0], plain(x[6] + ' ' + x[8] + ' ' + x[9])) for x in rows])
    except sqlite3.Error as e:
        print('[跳过全文检索]', e)
    con.commit()
    print('已写入 %d 题 -> %s' % (len(rows), Path(args.db).resolve()))
    print('表大小: %.1f KB' % (Path(args.db).stat().st_size / 1024))
    con.close()


if __name__ == '__main__':
    main()
