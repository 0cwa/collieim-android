from pathlib import Path
import subprocess,runpy,json,statistics,xml.etree.ElementTree as E
E.register_namespace('', 'http://www.w3.org/2000/svg')
r=Path(__file__).parent;m=runpy.run_path(str(r/'measure-fit.py'));raw=m['raw'];groups=m['groups'];ns={'s':'http://www.w3.org/2000/svg'}
def raster(svg,name):
 p=r/(name+'.png');subprocess.run(['rsvg-convert',str(svg),'-o',str(p)],check=True);return raw(p,0,0)
def wrap(name,w,h,paths):
 p=r/(name+'.svg');p.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">'+paths+'</svg>');return p
old=E.fromstring(subprocess.check_output(['git','show','670b216a:docs/site/assets/art/landscape-page.svg']).decode());op=old.findall('s:path',ns)
f_before=wrap('features-fit-before-recheck',1024,580,'<g transform="translate(0 -370)">'+''.join(E.tostring(p,encoding='unicode') for p in op[:3])+'</g>')
sets={
 'hero':dict(w=1024,h=516,reference='../background-a-flowing.png',crop=[0,0,1024,516],before='hero-fit-before-recheck.svg',after='hero-fit.svg',production='hero top-right identity; lower-left retains independently documented +24px clearance adaptation'),
 'features':dict(w=1024,h=580,reference='../background-a-flowing.png',crop=[0,500,1024,580],before=f_before.name,after='features-fit.svg',production='arch ribbons → landscape-page.svg translate(80,370), right sweep translate(0,370); page background then scales with its1024×3072 viewBox'),
 'collie':dict(w=800,h=360,reference='../background-c-collie-mural.png',crop=[0,1000,800,360],before='collie-fit-before-recheck.svg',after='collie-fit.svg',production='path coordinates unchanged; production viewBox800×300 intentionally crops lower empty area; existing responsive CSS placement unchanged')}
results=[]
def sample(a,w,x,lo,hi,kind,edge):
 hits=[]
 for y in range(lo,hi):
  rr,g,b,alpha=a[(y*w+x)*4:(y*w+x)*4+4]
  hit=(alpha>200) if kind=='alpha' else (alpha>200 and g>105 and rr>105 and b>65 and g>=rr*.98 and g>b*1.12)
  if hit:hits.append(y)
 runs=[q for q in groups(hits) if len(q)>=3]
 if not runs:return None
 q=runs[0]
 return q[0] if edge=='top' else q[-1] if edge=='bottom' else (q[0]+q[-1])/2
# Explicit sample windows form a sparse masked boundary diagnostic. They never include foreground text/phones/cards.
windows=[]
for x in [660,700,750,800,850,900,950,1000,1020]:
 for edge in ['top','bottom']:windows.append(('hero','upper lichen '+edge,x,45,125,edge))
for x in [0,50,100,150,200,250]:
 for edge in ['top','bottom']:windows.append(('hero','left lichen '+edge,x,430,516,edge))
for x in [350,400,450,600,650,700,750,800,850,900,950,1000,1020]:
 for edge in ['top','bottom']:windows.append(('features','feature arch '+edge,x,0,64 if x<550 else 145,edge))
for x in [900,950,1000,1020]:
 for edge in ['top','bottom']:windows.append(('features','right sweep '+edge,x,240,440,edge))
for x,lo,hi in [(570,42,76),(600,10,40),(620,0,27),(640,0,22),(660,0,28),(680,45,68),(700,60,80),(720,65,86),(740,70,97)]:windows.append(('collie','crest/head center',x,lo,hi,'center'))
for x,lo,hi in [(680,125,151),(700,97,115),(720,95,112),(740,75,97),(500,245,265),(540,240,261),(570,232,254),(600,230,247),(640,230,253),(700,253,275),(740,258,280),(760,247,272),(50,270,300),(100,265,295),(150,274,299),(200,267,295),(250,247,276),(300,235,263),(400,243,265)]:windows.append(('collie','muzzle/body/paw/tail center',x,lo,hi,'center'))
arrays={}
for key,s in sets.items():
 src=raw(r/s['reference'],0,0);fullw=1024
 # Selected C full raster is1024wide, crop is800wide. Extract rows without interpolation.
 x,y,w,h=s['crop'];ref=b''.join(src[((y+j)*fullw+x)*4:((y+j)*fullw+x+w)*4] for j in range(h))
 arrays[key]=(ref,raster(r/s['before'],key+'-before-registered'),raster(r/s['after'],key+'-after-registered'))
 for state in ['before','after']:
  root=E.parse(r/s[state]).getroot()
  for node in root.iter():
   if node.tag.endswith(('path','circle')):node.set('fill','none');node.set('stroke','#F000CC');node.set('stroke-width','1.5');node.set('opacity','1')
  E.ElementTree(root).write(r/(key+'-'+state+'-contour.svg'),encoding='unicode')
for key,label,x,lo,hi,edge in windows:
 s=sets[key];ref,before,after=arrays[key];v=sample(ref,s['w'],x,lo,hi,'pale',edge)
 bv=sample(before,s['w'],x,lo,hi,'alpha' if key=='collie' else 'pale',edge);av=sample(after,s['w'],x,lo,hi,'alpha' if key=='collie' else 'pale',edge)
 results.append(dict(region=key,boundary=label,x=x,window=[lo,hi],reference=v,before=bv,after=av,beforeOffset=None if v is None or bv is None else round(bv-v,2),afterOffset=None if v is None or av is None else round(av-v,2)))
summary={}
for key in sets:
 ss={}
 for phase in ['before','after']:
  vals=[abs(a[phase+'Offset']) for a in results if a['region']==key and a[phase+'Offset'] is not None]
  ss[phase]=dict(samples=len(vals),medianAbsolute=statistics.median(vals),meanAbsolute=round(statistics.mean(vals),2),maxAbsolute=max(vals))
 summary[key]=ss
manifest=dict(registration='Identity: crop pixels and vector viewBox units are1:1. No inferred global transform.',regions=sets,mask='Only listed x/window/edge samples count. All other pixels excluded, including AI phones/cards/text, tonal shading and orange mane. Color threshold in measure-fit.py; no whole-image pixel error.',samples=results,summary=summary)
(r/'registration-manifest.json').write_text(json.dumps(manifest,indent=2));print(json.dumps(summary,indent=2))
