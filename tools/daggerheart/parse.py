"""Parser for the exported Daggerheart TXT files (testdata/*.txt)."""
import re

FIELD = re.compile(r'^([A-Za-z0-9 ]+?): ?(.*)$')


def unescape(v):
    v = v.strip()
    if v == '\\0':
        return None
    return v.replace('\\,', ',')


def records(path):
    text = open(path, encoding='utf-8').read().replace('\r\n', '\n')
    blocks = re.split(r'\n\s*---\s*\n', text)
    result = []
    for block in blocks:
        rec = {}
        for line in block.split('\n'):
            if line.startswith('Format:'):
                continue
            m = FIELD.match(line)
            if m:
                rec[m.group(1)] = unescape(m.group(2))
        if rec:
            result.append(rec)
    return result


def markdown(html):
    """Converts the small HTML subset used by the export to markdown-lite."""
    if html is None:
        return ''
    s = html.replace('\\n', '\n')
    s = re.sub(r'</?(strong|b)>', '**', s)
    s = re.sub(r'</?(em|i)>', '*', s)
    s = re.sub(r'<li[^>]*>', '\n- ', s)
    s = re.sub(r'</li>', '', s)
    s = re.sub(r'</?ul[^>]*>', '\n', s)
    s = re.sub(r'</p>', '\n\n', s)
    s = re.sub(r'<br\s*/?>', '\n', s)
    s = re.sub(r'<[^>]+>', '', s)
    s = s.replace('&nbsp;', ' ').replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
    s = re.sub(r'\*\*\s*\*\*', '', s)
    s = re.sub(r'[ \t]+\n', '\n', s)
    s = re.sub(r'\n{3,}', '\n\n', s)
    return s.strip()
