from pathlib import Path
import copy,xml.etree.ElementTree as E
E.register_namespace('','http://www.w3.org/2000/svg');ns='{http://www.w3.org/2000/svg}';art=Path.cwd()/'docs/site/assets/art';source=E.parse(art/'collie-narrow.svg').getroot();draw=source.find(ns+'g');style=source.find(ns+'style').text
for narrow in [False,True]:
 width,offset=(1600,800) if narrow else (1280,480)
 root=E.Element(ns+'svg',{'viewBox':f'0 0 {width} 200','aria-hidden':'true','focusable':'false'});E.SubElement(root,ns+'style').text=style;group=E.SubElement(root,ns+'g',{'opacity':'.56'})
 # Preserve the actual lobe path. Recompose it under the resting forepaw:
 # outer tip(603,241) maps to(603,129), lower edge347 maps to195.
 orange=copy.deepcopy(draw[0]);orange.set('transform',f'translate({offset} -21.0566038) scale(1 .6226415094)');group.append(orange)
 if narrow:ground='M0 185C420 165 860 205 1120 185C1160 171 1195 148 1225 139L1225 145C1195 154 1162 180 1120 191C860 211 420 171 0 185Z'
 else:ground='M0 185C260 165 600 205 820 185C852 171 875 148 905 139L905 145C875 154 854 180 820 191C600 211 260 171 0 185Z'
 E.SubElement(group,ns+'path',{'class':'solid','d':ground})
 core=E.SubElement(group,ns+'g',{'transform':f'translate({offset} 0)'})
 for node in list(draw)[1:]:
  d=node.get('d','')
  if d.startswith('M670 117C703'):continue
  n=copy.deepcopy(node)
  if d.startswith(('M425 251','M488 258')):n.set('transform','translate(0 -112)')
  core.append(n)
 E.SubElement(core,ns+'path',{'class':'line','d':'M670 117C663 132 630 120.84 602 126'})
 E.ElementTree(root).write(art/('collie-scene-narrow.svg' if narrow else 'collie-scene-desktop.svg'),encoding='unicode')
