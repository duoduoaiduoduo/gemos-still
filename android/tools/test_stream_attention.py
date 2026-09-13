import numpy as np
import onnx,onnxruntime as ort
from onnx import helper as h,TensorProto as T
from stream_attention import patch

def model(batch=3):
 shape=[batch,2,7,4]
 tensors=[h.make_tensor_value_info(n,T.FLOAT16,s) for n,s in [('q',shape),('k',[batch,2,4,7]),('v',shape)]]
 return h.make_model(h.make_graph([h.make_node('MatMul',['q','k'],['scores'],name='mm1'),h.make_node('Softmax',['scores'],['p'],name='sm',axis=-1),h.make_node('MatMul',['p','v'],['o'],name='mm2')],'test',tensors,[h.make_tensor_value_info('o',T.FLOAT16,shape)],value_info=[h.make_tensor_value_info(n,T.FLOAT16,[batch,2,7,7]) for n in ['scores','p']]),opset_imports=[h.make_opsetid('',20)],ir_version=9)

for batch in [2,3,7]:
 m=model(batch)
 x={n:np.random.default_rng(i).normal(size=s).astype(np.float16) for i,(n,s) in enumerate([('q',(batch,2,7,4)),('k',(batch,2,4,7)),('v',(batch,2,7,4))])}
 original=ort.InferenceSession(m.SerializeToString()).run(None,x)[0]
 assert patch(m)==1
 onnx.checker.check_model(m)
 streamed=ort.InferenceSession(m.SerializeToString()).run(None,x)[0]
 np.testing.assert_allclose(original,streamed,atol=.004,rtol=.004)
 print('batch',batch,'max difference',float(abs(original-streamed).max()))
assert patch(model(1))==0
print('PASS: streaming attention preserves values and single-batch graphs')
