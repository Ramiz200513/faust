@echo off
cd /d C:\diplom\jarvis-ml
set PYTHONIOENCODING=utf-8
.venv\Scripts\python.exe -u train.py --base models\Qwen2.5-1.5B-Instruct --name qwen15-v1 --grad-ckpt --batch 8 --accum 2 --epochs 2 --val-sample 200 --cool-ms 800 --checkpoint-every 100 --resume >> runs\qwen15-v1.log 2>&1
echo EXIT %ERRORLEVEL% >> runs\qwen15-v1.log
