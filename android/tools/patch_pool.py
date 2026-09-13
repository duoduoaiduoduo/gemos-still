"""Preserve FP16 weights; execute pooling in FP32 to avoid ARM NHWC FP16 kernels."""
import sys
import onnx
from onnx import helper, TensorProto

def patch(model):
    types={v.name:v.type.tensor_type.elem_type for v in [*model.graph.input,*model.graph.value_info,*model.graph.output]}
    nodes=[]; count=0
    for node in model.graph.node:
        if node.op_type != 'AveragePool' or types.get(node.input[0]) != TensorProto.FLOAT16:
            nodes.append(node); continue
        original_in, original_out=node.input[0],node.output[0]
        fp_in,fp_out=original_in+'__pool_fp32',original_out+'__pool_fp32'
        nodes.append(helper.make_node('Cast',[original_in],[fp_in],name=node.name+'__cast_in',to=TensorProto.FLOAT))
        node.input[0]=fp_in;node.output[0]=fp_out
        nodes.append(node)
        nodes.append(helper.make_node('Cast',[fp_out],[original_out],name=node.name+'__cast_out',to=TensorProto.FLOAT16))
        count+=1
    del model.graph.node[:];model.graph.node.extend(nodes)
    return count

if __name__=='__main__':
    model=onnx.load(sys.argv[1],load_external_data=False)
    assert patch(model)==1, 'Unexpected model revision: review pool nodes'
    onnx.save(model,sys.argv[2])
