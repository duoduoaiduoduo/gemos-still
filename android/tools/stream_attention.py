"""Execute independent attention windows serially instead of materializing batch-sized scores."""
import onnx
from onnx import helper,TensorProto

def patch(model):
 shapes={v.name:[d.dim_value for d in v.type.tensor_type.shape.dim] for v in [*model.graph.input,*model.graph.value_info,*model.graph.output]}
 types={v.name:v.type.tensor_type.elem_type for v in [*model.graph.input,*model.graph.value_info,*model.graph.output]}
 consumers={}
 for n in model.graph.node:
  for name in n.input:consumers.setdefault(name,[]).append(n)
 replaced={};removed=set();count=0
 for n in model.graph.node:
  if n.op_type!='MatMul':continue
  s=shapes.get(n.output[0],[])
  if len(s)!=4 or s[0]<=1:continue
  users=consumers.get(n.output[0],[])
  if len(users)!=1 or users[0].op_type!='Softmax':continue
  sm=users[0]
  axis=next((a.i for a in sm.attribute if a.name=='axis'),-1)
  if axis not in (-1,3):continue
  users=consumers.get(sm.output[0],[])
  if len(users)!=1 or users[0].op_type!='MatMul' or users[0].input[0]!=sm.output[0]:continue
  end=users[0];q,k=n.input;v=end.input[1];outshape=shapes.get(end.output[0],[])
  if len(outshape)!=4 or outshape[0]!=s[0]:continue
  if any(shapes.get(t,[0])[0]!=s[0] for t in (q,k,v)):continue
  tag=f'stream_attn_{count}';dtype=types[n.output[0]]
  body_nodes=[helper.make_node('Gather',[name,'iteration'],[f'{tag}_{label}'],axis=0) for name,label in [(q,'q'),(k,'k'),(v,'v')]]
  body_nodes += [helper.make_node('MatMul',[tag+'_q',tag+'_k'],[tag+'_scores']),helper.make_node('Softmax',[tag+'_scores'],[tag+'_prob'],axis=-1),helper.make_node('MatMul',[tag+'_prob',tag+'_v'],[tag+'_out']),helper.make_node('Identity',['cond'],['cond_out'])]
  body=helper.make_graph(body_nodes,tag,[helper.make_tensor_value_info('iteration',TensorProto.INT64,[]),helper.make_tensor_value_info('cond',TensorProto.BOOL,[])],[helper.make_tensor_value_info('cond_out',TensorProto.BOOL,[]),helper.make_tensor_value_info(tag+'_out',dtype,outshape[1:])])
  model.graph.initializer.extend([helper.make_tensor(tag+'_count',TensorProto.INT64,[],[s[0]]),helper.make_tensor(tag+'_cond',TensorProto.BOOL,[],[True])])
  replaced[n.name]=helper.make_node('Loop',[tag+'_count',tag+'_cond'],list(end.output),name=tag,body=body)
  removed.update([sm.name,end.name]);count+=1
 nodes=[replaced.get(n.name,n) for n in model.graph.node if n.name not in removed]
 del model.graph.node[:];model.graph.node.extend(nodes)
 # Discard stale annotations for removed intermediate tensors.
 produced={o for n in nodes for o in n.output}
 info=[v for v in model.graph.value_info if v.name in produced]
 del model.graph.value_info[:];model.graph.value_info.extend(info)
 return count

if __name__=='__main__':
 import sys
 m=onnx.load(sys.argv[1],load_external_data=False);print('streamed attention blocks:',patch(m));onnx.save(m,sys.argv[2])
