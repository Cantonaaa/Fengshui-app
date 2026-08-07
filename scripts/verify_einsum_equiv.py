#!/usr/bin/env python3
"""验证 einsum->conv2d 等价替换：同一权重下，conv 版 forward 与原始 einsum 版 forward 输出一致。"""
import inspect
import textwrap
import torch
import importlib.util

spec = importlib.util.spec_from_file_location("m", "yolov8s_worldv2_pnnx.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
Model = m.Model

cur_forward = inspect.getsource(Model.forward)

repl = [
    (
        "v_89 = F.conv2d(v_86, self.attn_455_w)\n        v_89r = v_89.reshape(1, 4, 13, 40, 40)\n        v_90, _ = torch.max(v_89r, dim=2, keepdim=False)",
        "v_87 = self.pnnx_fold_455_data\n        v_88 = v_86.reshape(1, 4, 32, 40, 40)\n        v_89 = torch.einsum('ijnkl,imjn->ijklm', v_88, v_87)\n        v_90, _ = torch.max(v_89, dim=-1, keepdim=False)",
    ),
    (
        "v_114 = F.conv2d(v_111, self.attn_545_w)\n        v_114r = v_114.reshape(1, 2, 13, 80, 80)\n        v_115, _ = torch.max(v_114r, dim=2, keepdim=False)",
        "v_112 = self.pnnx_fold_545_data\n        v_113 = v_111.reshape(1, 2, 32, 80, 80)\n        v_114 = torch.einsum('ijnkl,imjn->ijklm', v_113, v_112)\n        v_115, _ = torch.max(v_114, dim=-1, keepdim=False)",
    ),
    (
        "v_140 = F.conv2d(v_137, self.attn_639_w)\n        v_140r = v_140.reshape(1, 4, 13, 40, 40)\n        v_141, _ = torch.max(v_140r, dim=2, keepdim=False)",
        "v_138 = self.pnnx_fold_639_data\n        v_139 = v_137.reshape(1, 4, 32, 40, 40)\n        v_140 = torch.einsum('ijnkl,imjn->ijklm', v_139, v_138)\n        v_141, _ = torch.max(v_140, dim=-1, keepdim=False)",
    ),
    (
        "v_166 = F.conv2d(v_163, self.attn_733_w)\n        v_166r = v_166.reshape(1, 8, 13, 20, 20)\n        v_167, _ = torch.max(v_166r, dim=2, keepdim=False)",
        "v_164 = self.pnnx_fold_733_data\n        v_165 = v_163.reshape(1, 8, 32, 20, 20)\n        v_166 = torch.einsum('ijnkl,imjn->ijklm', v_165, v_164)\n        v_167, _ = torch.max(v_166, dim=-1, keepdim=False)",
    ),
    (
        "v_191 = F.conv2d(v_189, self.head_cls_w)",
        "v_190 = self.pnnx_fold_831_data\n        v_191 = torch.einsum('imkl,ijm->ijkl', v_189, v_190)",
    ),
    (
        "v_204 = F.conv2d(v_203, self.head_cls_w)",
        "v_204 = torch.einsum('imkl,ijm->ijkl', v_203, v_190)",
    ),
    (
        "v_217 = F.conv2d(v_216, self.head_cls_w)",
        "v_217 = torch.einsum('imkl,ijm->ijkl', v_216, v_190)",
    ),
]

orig_forward_src = cur_forward
for cur, orig in repl:
    assert cur in orig_forward_src, f"未找到当前片段: {cur[:60]}"
    orig_forward_src = orig_forward_src.replace(cur, orig)

ns = {"torch": torch, "F": __import__("torch.nn.functional", fromlist=["x"])}
exec(textwrap.dedent(orig_forward_src), ns)
orig_forward = ns["forward"]
assert "einsum" in orig_forward_src, "还原后的 forward 不含 einsum，检查替换"

Model.forward = orig_forward
net_orig = Model()
net_orig.float()
net_orig.eval()

torch.manual_seed(0)
v = torch.rand(1, 3, 640, 640)
with torch.no_grad():
    o_orig = net_orig(v)

o_new = torch.load("/tmp/opencode/out_new_torch.pt", weights_only=True)
d = (o_new - o_orig).abs()
print("原始 einsum 输出 shape:", tuple(o_orig.shape))
print("max abs diff : %.8f" % d.max().item())
print("mean abs diff: %.10f" % d.mean().item())
print("相对误差     : %.8f" % (d.max().item() / o_orig.abs().max().item()))
print("一致" if d.max().item() < 1e-4 else "不一致!")
