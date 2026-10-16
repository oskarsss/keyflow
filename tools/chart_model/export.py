"""Convert the pinned human-trained mania checkpoint into portable cached ONNX graphs.

Usage: python -m tools.chart_model.export --upstream PATH --checkpoint PATH --output PATH
Install development dependencies in a venv; production inference needs only ONNX Runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from importlib.metadata import version

import numpy as np
import torch
from transformers.modeling_outputs import BaseModelOutput

from .portable import Encoder, Decoder

UPSTREAM_REVISION = '0e2b0e387aab4b35c64b0b11b12d47578dea7587'
MODEL_ID = 'OliBomby/Mapperatorinator-v32-mini'
MODEL_REVISION = '7807f0dc70cab671be012e1f5ddf945b0b8b7278'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    revision = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != UPSTREAM_REVISION:
        raise SystemExit('Check out the pinned upstream source revision before exporting.')
    sys.path.insert(0, str(args.upstream.resolve()))
    from osuT5.osuT5.model import Mapperatorinator
    from osuT5.osuT5.tokenizer import Tokenizer
    from onnxruntime.quantization import quantize_dynamic, QuantType
    import onnxruntime as ort

    torch.set_num_threads(2)
    torch.set_grad_enabled(False)
    torch.manual_seed(42)
    model = Mapperatorinator.from_pretrained(str(args.checkpoint), attn_implementation='sdpa').eval()
    assert model.config.hidden_size == 512 and len(model.transformer.model.decoder.layers) == 6
    assert model.config.vocab_size == 4097 and model.config.vocab_size_in == 12505
    tokenizer = Tokenizer.from_pretrained(str(args.checkpoint))
    encoder, decoder = Encoder(model).eval(), Decoder(model).eval()
    # Analytic audio: reference data is original, deterministic, and distributable.
    time = torch.arange(2047 * 128, dtype=torch.float64) / 16000
    audio = (.35 * torch.sin(2 * torch.pi * 220 * time) + .2 * torch.sin(2 * torch.pi * 330 * time))
    audio *= torch.exp(-((time * 2) % 1) * 7)
    audio = (audio * 30000).to(torch.int16).to(torch.float32) / 32768
    mel = model.spectrogram(audio[None]).transpose(1, 2)
    hidden = model.transformer.model.encoder(mel, return_dict=True).last_hidden_state
    own_hidden = encoder.hidden(mel)
    hidden_error = float((hidden - own_hidden).abs().max())
    assert hidden_error < 2e-4, hidden_error
    memory = encoder(mel)
    prompt = torch.tensor([[4097, 4104, 11735, 11753, 11811, 11914, 11933, 11948, 1, 5]])
    past = torch.zeros(6, 2, 1, 8, 0, 64)
    actual, cache = decoder(prompt, memory, past)
    # Requesting attention outputs forces the reference to build an explicit causal mask.
    # Upstream's uncached SDPA fast path otherwise elides it without setting is_causal.
    expected = model(encoder_outputs=BaseModelOutput(last_hidden_state=hidden),
                     decoder_input_ids=prompt, use_cache=False, output_attentions=True).logits[:, -1, :]
    logit_error = float((actual - expected).abs().max())
    assert logit_error < 3e-4, logit_error
    for token in [9, 1663, 4063]:
        step = torch.tensor([[token]])
        prompt = torch.cat((prompt, step), 1)
        actual, cache = decoder(step, memory, cache)
        expected = model(encoder_outputs=BaseModelOutput(last_hidden_state=hidden),
                         decoder_input_ids=prompt, use_cache=False, output_attentions=True).logits[:, -1, :]
        error = float((actual - expected).abs().max())
        logit_error = max(logit_error, error)
        assert error < 3e-4, error
    print('Portable graph parity:', hidden_error, logit_error, flush=True)

    args.output.mkdir(parents=True, exist_ok=True)
    encoder_path = args.output / 'chart-encoder-f32.onnx'
    decoder_path = args.output / 'chart-decoder-f32.onnx'
    torch.onnx.export(encoder, (mel,), str(encoder_path), input_names=['mel'], output_names=['memory'],
                      opset_version=17, dynamo=False, external_data=False)
    torch.onnx.export(decoder, (prompt, memory, past), str(decoder_path),
                      input_names=['ids', 'memory', 'past'], output_names=['logits', 'present'],
                      dynamic_axes={'ids': {1: 'tokens'}, 'past': {4: 'history'}, 'present': {4: 'total'}},
                      opset_version=17, dynamo=False, external_data=False)
    options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
    sessions = [ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
                for path in [encoder_path, decoder_path]]
    ort_memory = sessions[0].run(None, {'mel': mel.numpy()})[0]
    assert np.max(np.abs(ort_memory - memory.numpy())) < 2e-3
    ort_logits, ort_cache = sessions[1].run(None, {'ids': prompt.numpy(), 'memory': ort_memory, 'past': past.numpy()})
    reference, _ = decoder(prompt, memory, past)
    assert np.max(np.abs(ort_logits - reference.numpy())) < 2e-3
    # Check zero-length prefill and subsequent cached single-token steps independently.
    for token in [9, 1663]:
        ids = np.array([[token]], dtype=np.int64)
        ort_logits, ort_cache = sessions[1].run(None, {'ids': ids, 'memory': ort_memory, 'past': ort_cache})
        prompt = torch.cat((prompt, torch.from_numpy(ids)), 1)
        reference, _ = decoder(prompt, memory, past)
        assert np.max(np.abs(ort_logits - reference.numpy())) < 2e-3
    del sessions

    files = {}
    for source in [encoder_path, decoder_path]:
        target = source.with_name(source.name.replace('-f32', '-int8'))
        quantize_dynamic(str(source), str(target), weight_type=QuantType.QInt8, per_channel=True,
                         op_types_to_quantize=['MatMul'])
        files[target.name] = {'bytes': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
        print('Quantized', target, target.stat().st_size, flush=True)
    keys = ['offset', 'context_sos', 'context_eos', 'event_ranges', 'input_event_ranges',
            'event_range', 'event_start', 'event_end', 'num_diff_classes', 'max_difficulty',
            'num_mapper_classes', 'num_cs_classes', 'vocab_size_out', 'vocab_size_in', 'descriptor_idx']
    # The checkpoint JSON is already a portable representation of enums and event ranges.
    full = json.loads((args.checkpoint / 'tokenizer.json').read_text())
    (args.output / 'tokenizer.json').write_text(json.dumps({k: full[k] for k in keys}, indent=2) + '\n')
    tokenizer_path = args.output / 'tokenizer.json'
    files[tokenizer_path.name] = {'bytes': tokenizer_path.stat().st_size, 'sha256': hashlib.sha256(tokenizer_path.read_bytes()).hexdigest()}
    manifest = {'model': MODEL_ID, 'revision': MODEL_REVISION, 'subfolder': 'gamemode=3',
                'upstream': 'https://github.com/OliBomby/Mapperatorinator', 'upstream_revision': UPSTREAM_REVISION,
                'license': 'MIT', 'parameters': sum(p.numel() for p in model.parameters()),
                'checkpoint_sha256': hashlib.sha256((args.checkpoint / 'model.safetensors').read_bytes()).hexdigest(),
                'export_versions': {name: version(name) for name in ['torch', 'torchaudio', 'transformers', 'onnx', 'onnxruntime']},
                'frontend': {'rate': 16000, 'fft': 1024, 'hop': 128, 'frames': 2048,
                             'bins': 128, 'mel': 'htk', 'f_min': 20, 'f_max': 8000,
                             'power': 2, 'transform': 'log1p', 'normalize': 'whole-song peak'},
                'parity': {'encoder_max_abs': hidden_error, 'decoder_max_abs': logit_error}, 'files': files}
    (args.output / 'model.json').write_text(json.dumps(manifest, indent=2) + '\n')
    selected = model.spectrogram(audio[None])[0, [0, 1, 256, 512, 1024, 2047]].numpy().astype('<f4')
    (args.output / 'frontend-reference.f32').write_bytes(selected.tobytes())
    print('Export and fp32 cached-inference checks passed.', flush=True)


if __name__ == '__main__':
    main()
