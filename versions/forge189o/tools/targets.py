# 產生 mixin 目標：捐贈類別 ∪ forge_at.cfg 涉及的類別（執行期名稱），只留用戶端 jar 有的；分類別/介面。輸出 out-<ns>/targets.txt
import sys
ns=sys.argv[1]
T='C:/Temp/s1port/forgetool'
col={'named':5,'intermediary':4}[ns]
rt2off={}
for l in open(f'{T}/tool.tiny',encoding='utf8'):
    p=l.rstrip('\n').split('\t')
    if p[0]=='c': rt2off[p[col-1] if False else p[{'named':3,'intermediary':2}[ns]]]=p[1]
client=set(open(f'{T}/client_official.txt').read().split())
kind={}
for l in open(f'{T}/vkinds-{ns}.txt'):
    k,n=l.split(); kind[n]=k
cmap={}
for l in open(f'{T}/srg2rt-{ns}.txt',encoding='utf8'):
    p=l.split()
    if p[0]=='C': cmap[p[1]]=p[2]
names=[l.split()[1] for l in open(f'{T}/out-{ns}/donors.txt')]
for l in open('C:/Temp/s1port/forge189src/forge_at.cfg',encoding='utf8'):
    l=l.split('#')[0].strip()
    if not l: continue
    c=l.split()[1].replace('.','/')
    rt=cmap.get(c)
    if rt is None: print('?? AT class', c); continue
    names.append(rt)
out=[]; seen=set(); drop=[]
for n in names:
    if n in seen: continue
    seen.add(n)
    off=rt2off.get(n)
    if off is not None and off not in client: drop.append(n); continue
    out.append((kind.get(n,'C'),n))
with open(f'{T}/out-{ns}/targets.txt','w') as f:
    for k,n in out: f.write(f'{k} {n}\n')
print(ns,len(out),'targets; server-only dropped',len(drop), sum(1 for k,_ in out if k=='I'),'interfaces')
