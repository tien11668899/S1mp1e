# 產生 forge-srg.tiny（tiny v2，命名空間 official srg intermediary named）：給 Forge 模組 SRG→執行期名稱重映射用
import collections
SRG='C:/Users/Administrator/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp/1.8.9/joined.srg'
T='C:/Users/Administrator/.gradle/caches/fabric-loom/1.8.9/loom.mappings.1_8_9.layered+hash.997091829-v2/mappings.tiny'
srgc={}; srgf={}; srgm={}
for l in open(SRG,encoding='utf8'):
    p=l.split()
    if p[0]=='CL:': srgc[p[1]]=p[2]
    elif p[0]=='FD:':
        o,_,n=p[1].rpartition('/'); srgf[(o,n)]=p[2].rpartition('/')[2]
    elif p[0]=='MD:':
        o,_,n=p[1].rpartition('/'); srgm[(o,n,p[2])]=p[3].rpartition('/')[2]
# 讀 loom tiny
cls=collections.OrderedDict(); cur=None
for l in open(T,encoding='utf8'):
    if l.startswith('tiny'): continue
    p=l.rstrip('\n').split('\t')
    if p[0]=='c': cur=p[1]; cls[cur]={'n':(p[2],p[3]),'f':[],'m':[]}
    elif len(p)>=5 and p[0]=='' and p[1] in('f','m'):
        cls[cur][p[1]].append((p[2],p[3],p[4],p[5] if len(p)>5 else p[4]))
miss=collections.Counter()
out=open('C:/Temp/s1port/forgetool/forge-srg.tiny','w',encoding='utf8',newline='\n')
out.write('tiny\t2\t0\tofficial\tsrg\tintermediary\tnamed\n')
for o,c in cls.items():
    s=srgc.get(o)
    if s is None: miss['class']+=1; s=c['n'][0] if not c['n'][0].startswith('net/minecraft/unmapped') else o
    out.write(f'c\t{o}\t{s}\t{c["n"][0]}\t{c["n"][1]}\n')
    for d,on,i,n in c['f']:
        sn=srgf.get((o,on))
        if sn is None: miss['field']+=1; sn=on
        out.write(f'\tf\t{d}\t{on}\t{sn}\t{i}\t{n}\n')
    for d,on,i,n in c['m']:
        sn=srgm.get((o,on,d))
        if sn is None: miss['method']+=1; sn=on
        out.write(f'\tm\t{d}\t{on}\t{sn}\t{i}\t{n}\n')
out.close()
print('classes',len(cls),'srg classes',len(srgc),'missing',dict(miss))
# 反查：srg 裡有、loom 沒有的類別
print('srg-only classes', [k for k in srgc if k not in cls][:10])
