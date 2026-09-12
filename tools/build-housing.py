import bpy, math, json, os
from mathutils import Vector
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
def cube(name,dims,pos,r=0):
 bpy.ops.mesh.primitive_cube_add(size=1,location=(pos[0],-pos[2],pos[1]));o=bpy.context.object;o.name=name;o.dimensions=(dims[0],dims[2],dims[1]);bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
 if r:
  m=o.modifiers.new('Molded corners','BEVEL');m.width=r;m.segments=5;bpy.ops.object.modifier_apply(modifier=m.name)
 return o
def cut(target,cutter):
 bpy.context.view_layer.objects.active=target;m=target.modifiers.new('Continuous window opening','BOOLEAN');m.operation='DIFFERENCE';m.solver='EXACT';m.object=cutter;bpy.ops.object.modifier_apply(modifier=m.name);bpy.data.objects.remove(cutter,do_unlink=True)
def aperture(name,w,h,d,pos,axis):
 c=.14;pts=[(-w/2,-h/2),(w/2,-h/2),(w/2,h/2-c),(w/2-c,h/2),(-w/2+c,h/2),(-w/2,h/2-c)]
 verts=[]
 for t in [-d/2,d/2]:
  for u,v in pts:
   p=(u,v,t) if axis=='front' else (t,v,u) if axis=='side' else (u,t,v)
   x,y,z=[p[i]+pos[i] for i in range(3)];verts.append((x,-z,y))
 n=len(pts);faces=[tuple(range(n-1,-1,-1)),tuple(range(n,n*2))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
 mesh=bpy.data.meshes.new(name);mesh.from_pydata(verts,[],faces);mesh.update();o=bpy.data.objects.new(name,mesh);bpy.context.collection.objects.link(o)
 bpy.context.view_layer.objects.active=o;o.select_set(True)
 bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.mesh.normals_make_consistent(inside=False);bpy.ops.object.mode_set(mode='OBJECT')
 return o
shell=cube('One-piece monitor housing',(3.52,3.16,1.70),(0,.84,-.085),.045)
cut(shell,cube('Hollow interior',(3.17,2.81,1.46),(0,.84,-.085),.028))
cut(shell,aperture('Front and rear glazing',3.075,2.76,2.1,(0,.84,-.085),'front'))
cut(shell,aperture('Side glazing',1.255,2.72,4,(0,.84,-.085),'side'))
cut(shell,cube('Roof glazing',(3.075,.5,1.255),(0,2.4,-.085),.035))
bpy.context.view_layer.objects.active=shell
m=shell.modifiers.new('Window reveal edge radii','BEVEL');m.width=.012;m.segments=3;m.limit_method='ANGLE';m.angle_limit=.6;bpy.ops.object.modifier_apply(modifier=m.name)
for poly in shell.data.polygons:poly.use_smooth=True
m=shell.modifiers.new('Weighted manufacturing normals','WEIGHTED_NORMAL');m.keep_sharp=True;m.weight=50;bpy.ops.object.modifier_apply(modifier=m.name)
shell.data.calc_loop_triangles();pos=[];norm=[]
for tri in shell.data.loop_triangles:
 for li in tri.loops:
  v=shell.matrix_world@shell.data.vertices[shell.data.loops[li].vertex_index].co;n=shell.data.corner_normals[li].vector
  pos.extend([round(v.x,7),round(v.z,7),round(-v.y,7)]);norm.extend([round(n.x,7),round(n.z,7),round(-n.y,7)])
# A solid with one connected boundary, no interpenetrating frame components.
import bmesh
bm=bmesh.new();bm.from_mesh(shell.data);bad=sum(not e.is_manifold for e in bm.edges);print('NON_MANIFOLD_EDGES',bad,flush=True);assert bad==0
with open(os.path.join(ROOT,'housing.js'),'w') as f:f.write('export const housingData='+json.dumps({'position':pos,'normal':norm},separators=(',',':'))+';\n')
print('HOUSING_EXPORTED',len(pos)//9,'triangles',flush=True)
# Lower casing: one hollow solid; openings are cut through it, not overlaid panels.
profile=[(-1.01,-1.70),(2.015,-1.70),(2.015,-1.461),(.85,-1.138),(.792,-.845),(-1.01,-.845)]
verts=[(x,-z,y) for x in [-1.82,1.82] for z,y in profile];n=len(profile)
faces=[tuple(range(n-1,-1,-1)),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
mesh=bpy.data.meshes.new('Lower solid');mesh.from_pydata(verts,[],faces);mesh.update();base=bpy.data.objects.new('One-piece lower casing',mesh);bpy.context.collection.objects.link(base)
bpy.ops.object.select_all(action='DESELECT');base.select_set(True);bpy.context.view_layer.objects.active=base;bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT');bpy.ops.mesh.normals_make_consistent(inside=False);bpy.ops.object.mode_set(mode='OBJECT')
m=base.modifiers.new('Outer casing radius','BEVEL');m.width=.025;m.segments=4;bpy.ops.object.modifier_apply(modifier=m.name)
cut(base,cube('Hollow lower interior',(3.38,.43,2.68),(0,-1.375,.43),.012))
well=cube('Keyboard recess',(3.235,.45,1.015),(0,-1.4314,1.3933),.014);well.rotation_euler.x=.27;cut(base,well)
drive=cube('Drive recess',(1.00,.269,.32),(.98,-1.005,.813),.009);drive.rotation_euler.x=-.14;cut(base,drive)
for side in [-1,1]:
 for i in range(9):cut(base,cube('Ventilation opening',(.40,.024,.70),(side*1.79,-1.005-i*.049,-.43),.005))
bpy.context.view_layer.objects.active=base
m=base.modifiers.new('Recess edge relief','BEVEL');m.width=.004;m.segments=2;m.limit_method='ANGLE';m.angle_limit=.6;bpy.ops.object.modifier_apply(modifier=m.name)
for poly in base.data.polygons:poly.use_smooth=True
m=base.modifiers.new('Weighted casing normals','WEIGHTED_NORMAL');m.keep_sharp=True;bpy.ops.object.modifier_apply(modifier=m.name)
bm=bmesh.new();bm.from_mesh(base.data);bad=sum(not e.is_manifold for e in bm.edges);print('BASE_NON_MANIFOLD_EDGES',bad,flush=True);assert bad==0
base.data.calc_loop_triangles();pos=[];norm=[]
for tri in base.data.loop_triangles:
 for li in tri.loops:
  v=base.matrix_world@base.data.vertices[base.data.loops[li].vertex_index].co;n=base.data.corner_normals[li].vector
  pos.extend([round(v.x,7),round(v.z,7),round(-v.y,7)]);norm.extend([round(n.x,7),round(n.z,7),round(-n.y,7)])
with open(os.path.join(ROOT,'housing.js'),'a') as f:f.write('export const baseHousingData='+json.dumps({'position':pos,'normal':norm},separators=(',',':'))+';\n')
print('BASE_EXPORTED',len(pos)//9,'triangles',flush=True)
