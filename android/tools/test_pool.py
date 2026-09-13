import numpy as np
import onnx, onnxruntime as ort
from onnx import helper, TensorProto
from patch_pool import patch
x=np.random.default_rng(8).uniform(-3,3,(1,8,12,12)).astype(np.float16)
a=helper.make_tensor_value_info('x',TensorProto.FLOAT16,list(x.shape))
b=helper.make_tensor_value_info('y',TensorProto.FLOAT16,[1,8,6,6])
n=helper.make_node('AveragePool',['x'],['y'],name='pool',kernel_shape=[2,2],strides=[2,2],count_include_pad=1)
m=helper.make_model(helper.make_graph([n],'pool',[a],[b]),opset_imports=[helper.make_opsetid('',20)],ir_version=9)
assert patch(m)==1
onnx.checker.check_model(m)
s=ort.InferenceSession(m.SerializeToString(),providers=['CPUExecutionProvider'])
y=s.run(None,{'x':x})[0]
expected=x.astype(np.float32).reshape(1,8,6,2,6,2).mean(axis=(3,5)).astype(np.float16)
np.testing.assert_array_equal(y,expected)
print('PASS: patched pool matches FP32 reference rounded to FP16 exactly')
