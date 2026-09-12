import bpy, json, math, os, sys, array, time
from mathutils import Vector
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out=os.path.join(ROOT,'baked')
parts=json.load(open(os.path.join(out,'source.json')))
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.device='CPU';scene.cycles.samples=96
scene.cycles.max_bounces=8;scene.cycles.diffuse_bounces=6;scene.cycles.glossy_bounces=4
scene.render.bake.use_pass_direct=True;scene.render.bake.use_pass_indirect=True;scene.render.bake.use_pass_color=False
scene.render.bake.margin=8;scene.render.bake.use_clear=True
scene.world.use_nodes=True;scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.52,.56,.62,1);scene.world.node_tree.nodes['Background'].inputs[1].default_value=.17
verts=[];faces=[];normals=[];face_mats=[];metadata=[];source_corners=[];source_count=0
for mi,p in enumerate(parts):
 lookup={};local=[];pn=[]
 for j in range(0,len(p['positions']),3):
  x,y,z=p['positions'][j:j+3];co=(x,-z,y);key=tuple(round(v,7) for v in co)
  if key not in lookup:lookup[key]=len(verts);verts.append(co)
  local.append(lookup[key]);x,y,z=p['normals'][j:j+3];pn.append((x,-z,y))
 for j in range(0,len(local),3):
  f=tuple(local[j:j+3])
  if len(set(f))<3:continue
  faces.append(f);face_mats.append(mi);source_corners.append(source_count+j);normals.extend(pn[j:j+3])
 metadata.append({'id':p['id'],'count':len(local),'offset':source_count*2});source_count+=len(local)
mesh=bpy.data.meshes.new('Chassis');mesh.from_pydata(verts,[],faces);mesh.update()
obj=bpy.data.objects.new('Chassis',mesh);scene.collection.objects.link(obj);bpy.context.view_layer.objects.active=obj;obj.select_set(True)
image=bpy.data.images.new('Indirect and direct irradiance',width=4096,height=4096,alpha=False,float_buffer=True);image.colorspace_settings.name='Non-Color'
abs_height=bpy.data.images.load(os.path.join(out,'abs-height.png'));abs_height.colorspace_settings.name='Non-Color'
for p in parts:
 mat=bpy.data.materials.new('part_'+str(p['id']));mat.use_nodes=True
 bs=mat.node_tree.nodes.get('Principled BSDF');bs.inputs['Base Color'].default_value=(*p['color'],1);bs.inputs['Roughness'].default_value=.55
 if p.get('surface')=='molded-abs':
  geo=mat.node_tree.nodes.new('ShaderNodeNewGeometry');mapping=mat.node_tree.nodes.new('ShaderNodeVectorMath');mapping.operation='SCALE';mapping.inputs[3].default_value=1/1.6
  height=mat.node_tree.nodes.new('ShaderNodeTexImage');height.image=abs_height;height.projection='BOX';height.projection_blend=.2;height.extension='REPEAT'
  bump=mat.node_tree.nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=1;bump.inputs['Distance'].default_value=.0018
  links=mat.node_tree.links;links.new(geo.outputs['Position'],mapping.inputs[0]);links.new(mapping.outputs['Vector'],height.inputs['Vector']);links.new(height.outputs['Color'],bump.inputs['Height']);links.new(bump.outputs['Normal'],bs.inputs['Normal'])
  bs.inputs['Roughness'].default_value=p.get('roughness',.49)
 tex=mat.node_tree.nodes.new('ShaderNodeTexImage');tex.image=image;mat.node_tree.nodes.active=tex;obj.data.materials.append(mat)
for poly,mi in zip(mesh.polygons,face_mats):poly.material_index=mi;poly.use_smooth=True
mesh.normals_split_custom_set(normals)
bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.uv.smart_project(angle_limit=math.radians(65),island_margin=.003,area_weight=.25);bpy.ops.object.mode_set(mode='OBJECT')
uv=array.array('f',[0])*(source_count*2)
for poly,base in zip(mesh.polygons,source_corners):
 for k,li in enumerate(poly.loop_indices):
  value=mesh.uv_layers.active.data[li].uv;uv[(base+k)*2]=value.x;uv[(base+k)*2+1]=value.y
with open(os.path.join(out,'lightmap-uv.bin'),'wb') as f:uv.tofile(f)
json.dump({'parts':metadata,'scale':4,'resolution':4096,'samples':96,'bounces':6,'ready':False},open(os.path.join(out,'manifest.json'),'w'))
# The surrounding floor participates in indirect bounces and contact occlusion.
bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-1.758));floor=bpy.context.object
mat=bpy.data.materials.new('Clean charcoal floor');mat.diffuse_color=(.065,.060,.052,1);mat.use_nodes=True;mat.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value=(.065,.060,.052,1);mat.node_tree.nodes['Principled BSDF'].inputs['Roughness'].default_value=.72;floor.data.materials.append(mat)
def area(name,position,target,power,size,color):
 data=bpy.data.lights.new(name,'AREA');data.energy=power;data.shape='DISK';data.size=size;data.color=color
 ob=bpy.data.objects.new(name,data);scene.collection.objects.link(ob);ob.location=(position[0],-position[2],position[1]);t=Vector((target[0],-target[2],target[1]));ob.rotation_euler=(t-ob.location).to_track_quat('-Z','Y').to_euler()
area('Established upper-left softbox',(-3.8,6,5.2),(0,.2,0),1700,1.7,(1,.94,.85))
area('Soft cool fill',(5,3,-3),(0,.5,0),180,3,(.80,.88,1))
bpy.ops.object.select_all(action='DESELECT');obj.select_set(True);bpy.context.view_layer.objects.active=obj
print('BAKE_START',flush=True);bpy.ops.object.bake(type='DIFFUSE');print('BAKE_COMPLETE',flush=True)
def save_linear(image, filename):
 width,height=image.size
 pixels=array.array('f',[0])*(width*height*4);image.pixels.foreach_get(pixels)
 maximum=max(pixels);print('IRRADIANCE_MAX',maximum,flush=True)
 for i in range(0,len(pixels),4):
  pixels[i]=max(0,min(1,pixels[i]/4));pixels[i+1]=max(0,min(1,pixels[i+1]/4));pixels[i+2]=max(0,min(1,pixels[i+2]/4));pixels[i+3]=1
 image.pixels.foreach_set(pixels);image.filepath_raw=os.path.join(out,filename);image.file_format='PNG';image.save()
save_linear(image,'irradiance.png')
# A clean ground receiver gets its actual Cycles shadow, not a painted ellipse.
floor.scale=(.08,.08,.08)
floor_image=bpy.data.images.new('Ground irradiance',width=1024,height=1024,alpha=False,float_buffer=True);floor_image.colorspace_settings.name='Non-Color'
node=mat.node_tree.nodes.new('ShaderNodeTexImage');node.image=floor_image;mat.node_tree.nodes.active=node
bpy.ops.object.select_all(action='DESELECT');floor.select_set(True);bpy.context.view_layer.objects.active=floor
bpy.ops.object.bake(type='DIFFUSE');save_linear(floor_image,'ground.png')
manifest_path=os.path.join(out,'manifest.json')
meta=json.load(open(manifest_path));meta['ready']=True;json.dump(meta,open(manifest_path,'w'))
print('BAKE_SAVED',flush=True)
