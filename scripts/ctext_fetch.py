#!/usr/bin/env python3
"""从 ctext 抓取指定章节原文，清洗为纯文本。

用法: python3 ctext_fetch.py "书名" chapter_id 输出路径 [chapter_id2 输出路径2 ...]
"""
import re
import html
import sys
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", errors="replace")


def clean(html_text: str) -> str:
    """提取 ctext 中文原文：取 content 容器，剔除英文翻译(span.etext)与行号。"""
    text = re.sub(r"<script.*?</script>", "", html_text, flags=re.S)
    text = re.sub(r"<style.*?</style>", "", text, flags=re.S)
    m = re.search(r'<div[^>]*id="content"[^>]*>(.*?)(?:<div id="wiki_footer|</body)',
                  text, re.S)
    body = m.group(1) if m else text
    # 剔除英文翻译块
    body = re.sub(r"<span[^>]*class=\"etext\"[^>]*>.*?</span>", "", body, flags=re.S)
    # <br> -> 换行
    body = re.sub(r"<br\s*/?>", "\n", body)
    body = re.sub(r"<[^>]+>", "\n", body)
    body = html.unescape(body)
    # 定位正文范围：正文起点标记之后、版本/页脚标记之前
    marker_start = "查看歷史"
    marker_end = "顯示各種版本"
    i = body.find(marker_start)
    if i != -1:
        body = body[i + len(marker_start):]
    else:
        i = body.rfind("翻譯顯示")
        if i != -1:
            body = body[i + len("翻譯顯示"):]
    j = body.find(marker_end)
    if j != -1:
        body = body[:j]
    lines = []
    skip_words = {"不顯示", "英文"}
    for ln in body.split("\n"):
        ln = ln.strip()
        if not ln:
            continue
        if ln in skip_words:
            continue
        if re.fullmatch(r"[\s:：\[\]（）()]*", ln):
            continue
        if re.fullmatch(r"[\d\s]+", ln):  # 行号
            continue
        if re.search(r"[A-Za-z]{3,}", ln):  # 残留英文
            continue
        lines.append(ln)
    # 去重连续段落
    out = []
    for ln in lines:
        if out and ln == out[-1]:
            continue
        out.append(ln)
    return "\n\n".join(out)


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    title = sys.argv[1]
    pairs = sys.argv[2:]
    assert len(pairs) % 2 == 0
    for i in range(0, len(pairs), 2):
        chapter = pairs[i]
        out_path = pairs[i + 1]
        url = f"https://ctext.org/wiki.pl?if=gb&chapter={chapter}"
        try:
            raw = fetch(url)
        except Exception as e:
            print(f"[ERROR] {title} chapter={chapter}: {e}")
            continue
        text = clean(raw)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"[OK] {title} chapter={chapter} -> {out_path} ({len(text)}字)")


if __name__ == "__main__":
    main()
