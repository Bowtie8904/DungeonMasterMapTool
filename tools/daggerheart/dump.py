import sys
from parse import records, markdown

f = sys.argv[1]
n = int(sys.argv[2]) if len(sys.argv) > 2 else 300
out = open(sys.argv[3], 'w', encoding='utf-8') if len(sys.argv) > 3 else sys.stdout
for r in records('../../testdata/' + f + '.txt'):
    print(r.get('Name'), '::', (markdown(r.get('Description')) or '').replace('\n', ' / ')[:n], file=out)
