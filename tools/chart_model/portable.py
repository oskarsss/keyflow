"""Exportable inference graph for the pinned Mapperatorinator V32-mini mania weights.

This implements the published model's inference equations using ordinary ONNX operators,
with explicit KV caches. It removes training, HF generation and Flash Attention dependencies
from the Android runtime. Original architecture and weights: OliBomby, MIT; see notices.
"""
from __future__ import annotations

import math
import torch
from torch import nn
import torch.nn.functional as F


def rms(x, module):
    epsilon = module.eps if module.eps is not None else torch.finfo(x.dtype).eps
    return x * torch.rsqrt(x.square().mean(-1, keepdim=True) + epsilon) * module.weight


def attend(q, k, v, mask=None):
    scores = (q @ k.transpose(-2, -1)) / math.sqrt(q.shape[-1])
    if mask is not None:
        scores = scores.masked_fill(mask, -1e30)
    return scores.softmax(-1) @ v


def merge(x):
    return x.transpose(1, 2).reshape(x.shape[0], x.shape[2], -1)


class Rotary(nn.Module):
    def __init__(self, attention, length):
        super().__init__()
        rotary = attention.rotary_emb
        frequencies = torch.arange(length)[:, None] * rotary.inv_freq[None, :]
        angles = torch.cat((frequencies, frequencies), -1)
        self.register_buffer('cos', angles.cos() * rotary.attention_scaling)
        self.register_buffer('sin', angles.sin() * rotary.attention_scaling)

    def forward(self, x, positions):
        half = x.shape[-1] // 2
        turned = torch.cat((-x[..., half:], x[..., :half]), -1)
        return x * self.cos[positions][None, None] + turned * self.sin[positions][None, None]


class Encoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.encoder = model.transformer.model.encoder
        self.rotary = nn.ModuleList([Rotary(layer.self_attn, 1024) for layer in self.encoder.layers])
        self.cross = nn.ModuleList([layer.cross_attn.Wkv for layer in model.transformer.model.decoder.layers])
        self.heads = model.transformer.config.encoder_attention_heads
        self.width = model.config.hidden_size // self.heads

    def hidden(self, mel):
        x = F.gelu(self.encoder.conv1(mel))
        x = F.gelu(self.encoder.conv2(x)).transpose(1, 2)
        positions = torch.arange(x.shape[1], device=x.device)
        for layer, rotary in zip(self.encoder.layers, self.rotary):
            a = layer.self_attn
            y = rms(x, layer.self_attn_layer_norm)
            q, k, v = a.Wqkv(y).reshape(1, -1, 3, self.heads, self.width).permute(2, 0, 3, 1, 4).unbind(0)
            x = x + a.Wo(merge(attend(rotary(q, positions), rotary(k, positions), v)))
            y = rms(x, layer.final_layer_norm)
            x = x + layer.fc2(F.gelu(layer.fc1(y)))
        return rms(x, self.encoder.layer_norm)

    def forward(self, mel):
        hidden = self.hidden(mel)
        return torch.stack([projection(hidden).reshape(1, -1, 2, self.heads, self.width)
                            .permute(2, 0, 3, 1, 4) for projection in self.cross])


class Decoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.embedding = model.decoder_embedder
        self.decoder = model.transformer.model.decoder
        self.projection = model.transformer.proj_out
        self.rotary = nn.ModuleList([Rotary(layer.self_attn, 2560) for layer in self.decoder.layers])
        self.heads = model.transformer.config.decoder_attention_heads
        self.width = model.config.hidden_size // self.heads

    def forward(self, ids, memory, past):
        x = self.embedding(ids)
        before = past.shape[-2]
        positions = torch.arange(x.shape[1], device=x.device) + before
        keys = torch.arange(before + x.shape[1], device=x.device)
        mask = keys[None, :] > positions[:, None]
        present = []
        for i, (layer, rotary) in enumerate(zip(self.decoder.layers, self.rotary)):
            a = layer.self_attn
            y = rms(x, layer.self_attn_layer_norm)
            q, k, v = a.Wqkv(y).reshape(1, -1, 3, self.heads, self.width).permute(2, 0, 3, 1, 4).unbind(0)
            q, k = rotary(q, positions), rotary(k, positions)
            k = torch.cat((past[i, 0], k), -2)
            v = torch.cat((past[i, 1], v), -2)
            present.append(torch.stack((k, v)))
            x = x + a.Wo(merge(attend(q, k, v, mask)))
            a = layer.cross_attn
            y = rms(x, layer.cross_attn_layer_norm)
            q = a.Wq(y).reshape(1, -1, self.heads, self.width).transpose(1, 2)
            x = x + a.Wo(merge(attend(q, memory[i, 0], memory[i, 1])))
            y = rms(x, layer.final_layer_norm)
            x = x + layer.fc2(F.gelu(layer.fc1(y)))
        logits = self.projection(rms(x, self.decoder.layer_norm)[:, -1, :])
        return logits, torch.stack(present)
