# FastConformer validation assets and attribution

This optional validation module uses the Spanish NVIDIA FastConformer Hybrid
Large P&C model from [NVIDIA](https://huggingface.co/nvidia/stt_es_fastconformer_hybrid_large_pc),
through the [OpenVoiceOS ONNX CTC export](https://huggingface.co/OpenVoiceOS/stt_es_fastconformer_hybrid_large_pc_onnx)
and [krut42's int8 CTC derivative](https://huggingface.co/krut42/voice-fastconformer-es-ctc-int8).
The derivative card identifies the model license as
[Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/legalcode).
The P&C model is the non-`_pc_nc` checkpoint.

Pinned derivative revision: `d7694ab533e189621361a30deb701f189abc7568`.
The pinned ONNX and token SHA-256 values are recorded in
`src/main/java/dev/sebastian/vozlocal/fastconformervalidation/FastConformerModelBundle.kt`.
This quantized derivative is used for local validation only; retain this notice
and the linked attribution if redistributing model-derived materials.

The isolated sherpa-onnx Android runtime is version `v1.13.8`, from the
[official release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8),
using its static-link ONNX Runtime AAR. Sherpa-ONNX is distributed under
[Apache License 2.0](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE).
