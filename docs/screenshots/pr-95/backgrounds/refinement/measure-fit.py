from pathlib import Path
import subprocess,json
r=Path(__file__).parent

def raw(p,w,h):return subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-f','rawvideo','-pix_fmt','rgba','-'])
def runs(a,w,x,lo,hi,kind):
 ys=[]
 for y in range(lo,hi):
  rr,g,b,al=a[(y*w+x)*4:(y*w+x)*4+4]
  hit=(g>105 and rr>105 and b>65 and g>=rr*.98 and g>b*1.12) if kind=='pale' else (rr>155 and 55<g<210 and b<100 and rr>g*1.25)
  if hit:ys.append(y)
 return [(g[0],g[-1]) for g in groups(ys) if len(g)>=3]
def groups(xs):
 a=[]
 for x in xs:
  if a and x!=a[-1]+1:yield a;a=[]
  a.append(x)
 if a:yield a
if __name__=='__main__':
 a=raw(r.parent/'line-concepts/background-a-flowing.png',1024,1536)
 subprocess.run(['rsvg-convert',str(r/'hero-fit.svg'),'-o',str(r/'registered-current.png')],check=True)
 b=raw(r/'registered-current.png',1024,516)
 for x in [620,660,700,750,800,850,900,950,1000,1020]:print(x,'raster',runs(a,1024,x,45,170,'pale'),'vector',runs(b,1024,x,45,170,'pale'),'gold',runs(a,1024,x,45,175,'gold'),runs(b,1024,x,45,175,'gold'))
