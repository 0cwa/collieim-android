from pathlib import Path
import subprocess,json,math
r=Path(__file__).parent;proof=json.loads((r/'resting-semantic.json').read_text());out=[]
for item in [p for p in proof if not p['off']]:
 w=item['w'];raw=lambda state:subprocess.check_output(['ffmpeg','-v','error','-i',str(r/f'resting-community-{state}-{w}.png'),'-f','rawvideo','-pix_fmt','rgb24','-']);on,off=raw('on'),raw('off');scroll=item['stage']['scrollY'];features=[]
 for feature in item['features']:
  x,y,rw,rh=feature['rect'];left,right=max(0,math.floor(x)),min(w,math.ceil(x+rw));top,bottom=math.floor(y-scroll),math.ceil(y+rh-scroll);differences=0
  for yy in range(top,bottom):
   a=on[(yy*w+left)*3:(yy*w+right)*3];b=off[(yy*w+left)*3:(yy*w+right)*3];differences+=sum(i!=j for i,j in zip(a,b))
  features.append({'feature':feature['name'],'differingChannels':differences,'bounds':feature['rect']})
 assert all(v['differingChannels']==0 for v in features),features
 stage=item['stage'];margins={'afterAction':round(stage['image'][1]-stage['lastActionBottom'],3),'beforeGet':round(stage['getTop']-stage['image'][1]-stage['image'][3],3)} if w<1101 else None
 out.append({'width':w,'essentialFeaturesUnoccluded':True,'features':features,'sceneMargins':margins})
(r/'resting-visibility-check.json').write_text(json.dumps(out,indent=2));print(json.dumps([{'width':x['width'],'features':len(x['features']),'allOnOffIdentical':x['essentialFeaturesUnoccluded'],'sceneMargins':x['sceneMargins']} for x in out],indent=2))
