"""Generate the original palette textures and precise cuboid models (no external assets)."""
from pathlib import Path
import json, struct, zlib
ROOT = Path(__file__).resolve().parents[1] / 'resourcepack'
ASSETS = ROOT / 'assets/amplified_items'
def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
def png(path, colours):
    path.parent.mkdir(parents=True, exist_ok=True)
    def chunk(name, data): return struct.pack('!I', len(data)) + name + data + struct.pack('!I', zlib.crc32(name+data) & 0xffffffff)
    data = b''.join(b'\0'+b''.join(bytes(colours[(x//3+y//5+(x*y)%3)%len(colours)]) for x in range(16)) for y in range(16))
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('!2I5B',16,16,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(data))+chunk(b'IEND',b''))
palettes = {
 'ebony': [(27,21,35),(44,28,48),(60,38,58)],
 'gold': [(221,166,42),(255,219,108),(181,117,28)],
 'purple': [(108,39,185),(176,90,235),(226,162,255)],
 'iron': [(47,67,65),(78,105,100),(123,150,133)],
 'wood': [(51,35,26),(78,51,31),(96,68,41)],
 'venom': [(61,174,34),(129,241,46),(193,255,97)],
 'string': [(173,193,174),(225,232,202),(125,147,124)],
 'shadow_steel': [(43,42,58),(64,61,82),(102,96,126)],
 'soul': [(112,52,174),(184,98,242),(220,163,255)],
 'ember': [(178,37,12),(249,92,18),(255,168,42)],
 'sunlight': [(232,159,21),(255,206,57),(255,238,137)],
 'ocean': [(16,45,79),(24,77,124),(40,116,155)],
 'sea_glass': [(19,129,146),(51,202,200),(123,249,221)],
 'moon_steel': [(30,29,62),(49,46,98),(78,73,147)],
 'moonlight': [(126,139,211),(173,183,247),(218,221,255)],
 'wind_bone': [(126,180,177),(188,228,218),(224,244,229)],
 'sky': [(39,118,150),(62,178,201),(136,224,224)],
 'web_silk': [(155,161,169),(202,210,216),(239,243,247)],
 'spider_steel': [(37,43,29),(73,87,47),(114,135,69)],
 'blood_steel': [(83,22,35),(139,32,45),(186,52,59)],
 'fang': [(184,82,79),(225,138,122),(255,191,162)]}
for name, cols in palettes.items(): png(ASSETS / f'textures/item/{name}.png', cols)
def box(frm, to, texture, rotation=None):
    e = {'from':frm,'to':to,'faces':{face:{'texture':'#'+texture,'uv':[0,0,16,16]} for face in ['north','south','east','west','up','down']}}
    if rotation: e['rotation']=rotation
    return e
display = {
 'thirdperson_righthand':{'rotation':[0,90,0],'translation':[0,1,0],'scale':[0.55,0.55,0.55]},
 'thirdperson_lefthand':{'rotation':[0,-90,0],'translation':[0,1,0],'scale':[0.55,0.55,0.55]},
 'firstperson_righthand':{'rotation':[0,-90,12],'translation':[1,2,-1],'scale':[0.65,0.65,0.65]},
 'firstperson_lefthand':{'rotation':[0,90,-12],'translation':[1,2,-1],'scale':[0.65,0.65,0.65]},
 'gui':{'rotation':[12,0,-30],'translation':[0,-4,0],'scale':[0.65,0.65,0.65]},
 'ground':{'rotation':[0,0,90],'translation':[0,2,0],'scale':[0.4,0.4,0.4]},
 'fixed':{'rotation':[0,0,0],'translation':[0,-4,0],'scale':[0.7,0.7,0.7]}}
staff = [box([7,0,7],[9,23,9],'ebony'), box([6.7,0,6.7],[9.3,2,9.3],'gold'),
 box([6.5,16,6.5],[9.5,18,9.5],'gold'),box([6,22,6],[10,24,10],'gold'),
 box([5,24,5],[11,28,11],'purple'),box([4.5,23.5,4.5],[11.5,24.5,11.5],'gold')]
for x,z in [(4.5,4.5),(10,4.5),(4.5,10),(10,10)]: staff.append(box([x,26,z],[x+1.5,30,z+1.5],'gold'))
staff.append(box([6.5,28,6.5],[9.5,29,9.5],'gold'))
write_json(ASSETS/'models/item/kings_decree.json',{'textures':{k:'amplified_items:item/'+k for k in palettes},'elements':staff,'display':display})
crossbow=[box([6,6,1],[10,9,16],'wood'),box([6.5,4,9],[9.5,6,13],'wood'),
 box([5.5,6,2],[10.5,9.5,5],'iron'),box([0,7,3],[6,9,5],'iron'),box([10,7,3],[16,9,5],'iron'),
 box([0,7,4],[2,9,7],'venom'),box([14,7,4],[16,9,7],'venom'),
 box([1,8,6],[15,8.4,6.4],'string'),box([7.5,9.5,0],[8.5,10.5,12],'gold'),
 box([7,9,-1],[9,11,2],'venom'),box([6,9.5,11],[10,10,13],'venom'),
 box([6,6.5,13],[10,9,15],'venom')]
xbdisp=json.loads(json.dumps(display));xbdisp['gui']={'rotation':[35,45,0],'translation':[0,0,0],'scale':[0.9,0.9,0.9]};xbdisp['firstperson_righthand']={'rotation':[0,-90,0],'translation':[1,0,-1],'scale':[0.9,0.9,0.9]}
write_json(ASSETS/'models/item/venombolt.json',{'textures':{k:'amplified_items:item/'+k for k in palettes},'elements':crossbow,'display':xbdisp})
reaper = [
 box([7,0,7],[9,22,9],'ebony'), box([6.5,0,6.5],[9.5,2,9.5],'shadow_steel'),
 box([6.5,10,6.5],[9.5,13,9.5],'soul'), box([6.5,20,6.5],[9.5,26,9.5],'shadow_steel'),
 box([7,24,7],[12,27,9],'shadow_steel'), box([11,22,7],[15,26,9],'shadow_steel'),
 box([14,19,7],[17,24,9],'shadow_steel'), box([16,16,7],[18,22,9],'shadow_steel'),
 box([7,26,7],[12,27,9],'soul'), box([12,25,7],[15,26,9],'soul'),
 box([15,23,7],[17,24,9],'soul'), box([17,19,7],[18,22,9],'soul'),
 box([17.5,15,7.25],[18.5,19,8.75],'soul')]
sun = [
 box([6,6,1],[10,9,16],'wood'), box([6.5,4,9],[9.5,6,13],'wood'),
 box([5,6,2],[11,10,6],'gold'), box([1,7,2],[6,9,5],'sunlight'),
 box([10,7,2],[15,9,5],'sunlight'), box([0,7,3],[2,9,7],'ember'),
 box([14,7,3],[16,9,7],'ember'), box([2,7,4],[4,9,7],'gold'),
 box([12,7,4],[14,9,7],'gold'), box([1,8,6],[15,8.5,6.5],'string'),
 box([7.5,9.5,0],[8.5,10.5,12],'gold'), box([6.5,9,-1],[9.5,11,2],'ember'),
 box([6,6,13],[10,9,15],'sunlight'), box([7,9.5,3],[9,11.5,5],'sunlight')]
leviathan = [
 box([7,0,7],[9,24,9],'ocean'), box([6.5,0,6.5],[9.5,2,9.5],'sea_glass'),
 box([6.5,9,6.5],[9.5,11,9.5],'sea_glass'), box([6,20,6],[10,23,10],'ocean'),
 box([4,22,7],[12,24,9],'sea_glass'), box([4,24,7],[6,29,9],'ocean'),
 box([7,24,7],[9,31,9],'ocean'), box([10,24,7],[12,29,9],'ocean'),
 box([4.3,28,7.3],[5.7,30,8.7],'sea_glass'), box([7.3,30,7.3],[8.7,32,8.7],'sea_glass'),
 box([10.3,28,7.3],[11.7,30,8.7],'sea_glass'), box([6.8,21,5.8],[9.2,23.5,7.2],'sea_glass')]
for name, elements, transforms in [('reapers_edge', reaper, display), ('sunpiercer', sun, xbdisp),
                                   ('leviathans_wrath', leviathan, display)]:
 write_json(ASSETS/f'models/item/{name}.json',{'textures':{k:'amplified_items:item/'+k for k in palettes},
                                           'elements':elements,'display':transforms})
nightblade = [
 box([7,0,7],[9,8,9],'ebony'), box([6,0,6.5],[10,2,9.5],'moon_steel'),
 box([3.5,7,7],[12.5,9,9],'moon_steel'), box([3.5,8,7],[5,11,9],'moonlight'),
 box([11,8,7],[12.5,11,9],'moonlight'), box([6,9,7.15],[10,26,8.85],'moon_steel'),
 box([5.25,10,7.6],[6,24,8.4],'moonlight'), box([10,10,7.6],[10.75,24,8.4],'moonlight'),
 box([6.5,26,7.25],[9.5,28,8.75],'moon_steel'), box([7.15,28,7.5],[8.85,30,8.5],'moonlight'),
 box([7.5,9,6.7],[8.5,21,7.15],'soul'), box([7.5,9,8.85],[8.5,21,9.3],'soul')]
solarblade = [
 box([7,0,7],[9,8,9],'wood'), box([6,0,6.5],[10,2,9.5],'gold'),
 box([3,7,6.75],[13,9.5,9.25],'gold'), box([3,8,7],[4.5,11,9],'ember'),
 box([11.5,8,7],[13,11,9],'ember'), box([5.7,9.5,7],[10.3,25,9],'sunlight'),
 box([4.9,10,7.45],[5.7,23,8.55],'ember'), box([10.3,10,7.45],[11.1,23,8.55],'ember'),
 box([6.4,25,7.2],[9.6,28,8.8],'sunlight'), box([7.2,28,7.6],[8.8,30,8.4],'sunlight'),
 box([7,9.5,6.5],[9,21,7],'gold'), box([7,9.5,9],[9,21,9.5],'gold')]
windwalker = [
 box([5.5,6,7],[8,11,9],'ocean'), box([5,4,7],[7,6,9],'wind_bone'),
 box([4,1.5,7],[6,4,9],'wind_bone'), box([3.6,0,7.2],[5.4,2,8.8],'sky'),
 box([5,11,7],[7,13,9],'wind_bone'), box([4,13,7],[6,15.5,9],'wind_bone'),
 box([3.6,15,7.2],[5.4,17,8.8],'sky'), box([4,1,7.85],[4.4,16,8.15],'web_silk'),
 box([5.25,6,6.65],[8.25,7.25,9.35],'sky'), box([5.25,9.75,6.65],[8.25,11,9.35],'sky'),
 box([7.9,7.25,7.4],[8.9,9.75,8.6],'sea_glass')]
wind_display = json.loads(json.dumps(display))
wind_display['gui'] = {'rotation':[0,0,-25],'translation':[1,0,0],'scale':[.95,.95,.95]}
spiders_bane = [
 box([7,0,7],[9,8,9],'ebony'), box([6.5,0,6.5],[9.5,2,9.5],'web_silk'),
 box([4,7,7],[12,9,9],'spider_steel'), box([4,8.5,7],[5.5,11,9],'web_silk'),
 box([10.5,8.5,7],[12,11,9],'web_silk'), box([6.2,9,7],[9.8,24,9],'spider_steel'),
 box([5.6,10,7.5],[6.2,22,8.5],'web_silk'), box([9.8,10,7.5],[10.4,22,8.5],'web_silk'),
 box([7,24,7.3],[9,28,8.7],'web_silk'), box([7.5,10,6.55],[8.5,19,7],'venom'),
 box([7.5,10,9],[8.5,19,9.45],'venom')]
bloodfang = [
 box([7,0,7],[9,8,9],'ebony'), box([6,0,6.5],[10,2,9.5],'blood_steel'),
 box([4,7,6.65],[12,9.5,9.35],'shadow_steel'), box([5.2,9.5,7],[11.4,24,9],'blood_steel'),
 box([4.55,11,7.55],[5.2,23,8.45],'fang'), box([11.4,11,7.45],[12.1,22,8.55],'fang'),
 box([6,24,7.25],[10.9,27,8.75],'blood_steel'), box([7,27,7.5],[10,30,8.5],'fang'),
 box([11.2,11,7.25],[13,14,8.75],'blood_steel'), box([11.2,19,7.25],[13,22,8.75],'blood_steel'),
 box([7.25,10,6.55],[8.75,20,7],'ember'), box([7.25,10,9],[8.75,20,9.45],'ember')]
new_models = [('nightblade', nightblade, display), ('solarblade', solarblade, display),
              ('windwalker', windwalker, wind_display), ('spiders_bane', spiders_bane, display),
              ('bloodfang', bloodfang, display)]
for name, elements, transforms in new_models:
    textures = {face['texture'][1:] for element in elements for face in element['faces'].values()}
    write_json(ASSETS/f'models/item/{name}.json', {'textures': {key: f'amplified_items:item/{key}' for key in sorted(textures)},
                                                 'elements': elements, 'display': transforms})
for name in ['kings_decree','venombolt','reapers_edge','sunpiercer','leviathans_wrath',
             'nightblade','solarblade','windwalker','spiders_bane','bloodfang']:
 write_json(ASSETS/f'items/{name}.json',{'model':{'type':'minecraft:model','model':f'amplified_items:item/{name}'}})
write_json(ROOT/'pack.mcmeta',{'pack':{'description':'Amplified Items for AmplifiedSMP','min_format':[97,1],'max_format':[97,1]}})
print('Generated resource pack:',ROOT)

