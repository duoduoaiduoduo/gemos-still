from pathlib import Path
import torch,gc,time
from sharp.models import create_predictor,PredictorParams
from sharp.models.encoders.spn_encoder import SlidingPyramidNetwork
from sharp.models.encoders.vit_encoder import TimmViT
import argparse
parser=argparse.ArgumentParser();parser.add_argument('--checkpoint',type=Path,required=True);parser.add_argument('--output-dir',type=Path,required=True);args=parser.parse_args();root=args.output_dir;root.mkdir(parents=True,exist_ok=True)
torch.set_num_threads(2)
p=PredictorParams();p.monodepth.use_patch_overlap=False
print('Creating predictor',flush=True)
m=create_predictor(p).eval()
checkpoint=args.checkpoint
s=torch.load(checkpoint,weights_only=True,mmap=True);m.load_state_dict(s);del s;gc.collect()
for module in m.modules():
 if isinstance(module,TimmViT):module.set_input_size(img_size=64)
 if isinstance(module,SlidingPyramidNetwork):module.patch_size=64
class Wrapper(torch.nn.Module):
 def __init__(self,p):super().__init__();self.predictor=p
 def forward(self,image,disparity_factor):
  g=self.predictor(image,disparity_factor)
  return g.mean_vectors,g.singular_values,g.quaternions,g.colors,g.opacities
w=Wrapper(m).eval();x=torch.full((1,3,256,256),.5);d=torch.tensor([.8])
with torch.inference_mode():
 print('Eager smoke test',flush=True);t=time.monotonic();g=w(x,d);print('Done',time.monotonic()-t,[list(v.shape) for v in g],flush=True)
 print('Export',flush=True)
 torch.onnx.export(w,(x,d),str(root/'lite256.onnx'),input_names=['image','disparity_factor'],output_names=['mean_vectors_ndc','singular_values_ndc','quaternions_ndc','colors','opacities'],opset_version=20,dynamo=True,external_data=True)
print('Export complete',flush=True)

# Run this separate stage with onnxruntime installed after exporting:
# quantize_dynamic("lite256.onnx", "lite256int8.onnx", op_types_to_quantize=["MatMul"],
#   per_channel=True, weight_type=QuantType.QInt8, use_external_data_format=True,
#   extra_options={"MatMulConstBOnly": True})
