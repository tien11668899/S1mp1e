# srg2rt-<ns>.txt：C mcp→rt、M srg→rt、A 歧義（intermediary 下同一 SRG 對到多個名字，依擁有者）
import collections
L=open('C:/Temp/s1port/forgetool/forge-srg.tiny',encoding='utf8').read().split('\n')
named={}; cur=None
for l in open('C:/Temp/s1port/forgetool/tool.tiny',encoding='utf8'):
    p=l.rstrip('\n').split('\t')
    if p[0]=='c': cur=p[1]; named[('c',cur)]=p[3]
    elif len(p)>=6 and p[1] in 'mf': named[(p[1],cur,p[3],p[2])]=p[5]
import re
off2srg={}
for l in L[1:]:
    q=l.split('	')
    if q[0]=='c': off2srg[q[1]]=q[2]
def sdesc(d): return re.sub(r'L([^;]+);',lambda m:'L'+off2srg.get(m.group(1),m.group(1))+';',d)
special={'intermediary':[],'named':[]}
res={'intermediary':collections.defaultdict(set),'named':collections.defaultdict(set)}
own={'intermediary':collections.defaultdict(list),'named':collections.defaultdict(list)}
cls={'intermediary':{},'named':{}}
for l in L[1:]:
    p=l.split('\t')
    if p[0]=='c': cur=p[1]; srgc=p[2]; cls['intermediary'][p[2]]=p[3]; cls['named'][p[2]]=named.get(('c',cur),p[4])
    elif len(p)>=7 and p[1] in 'mf':
        k,d,o,s,i,n=p[1],p[2],p[3],p[4],p[5],p[6]
        nn=named.get((k,cur,o,d),n)
        if not (s.startswith('func_') or s.startswith('field_')):
            if s!=i and not s.startswith('<'): special['intermediary'].append((k,srgc,s,sdesc(d),i))
            if s!=nn and not s.startswith('<'): special['named'].append((k,srgc,s,sdesc(d),nn))
            continue
        res['intermediary'][s].add(i); own['intermediary'][s].append((srgc,i))
        res['named'][s].add(nn); own['named'][s].append((srgc,nn))
for ns in res:
    amb={k for k,v in res[ns].items() if len(v)>1}
    with open(f'C:/Temp/s1port/forgetool/srg2rt-{ns}.txt','w',encoding='utf8',newline='\n') as f:
        for a,b in sorted(cls[ns].items()): f.write(f'C {a} {b}\n')
        for a,b in sorted(res[ns].items()): f.write(f'M {a} {sorted(b)[0]}\n')
        for k,o,n,d,r in special[ns]: f.write(f'S {o} {n} {d} {r}'+chr(10))
        for k in sorted(amb):
            for o,r in own[ns][k]: f.write(f'A {k} {o} {r}\n')
    print(ns,'members',len(res[ns]),'ambiguous',len(amb),'special',len(special[ns]))
