#!/usr/bin/env python3
"""
下载并转换YOLOv8n模型为TensorFlow Lite格式
"""

import os
import urllib.request
import sys

def download_file(url, output_path):
    """下载文件并显示进度"""
    print(f"正在下载: {url}")
    print(f"保存到: {output_path}")
    
    def reporthook(count, block_size, total_size):
        percent = int(count * block_size * 100 / total_size)
        sys.stdout.write(f"\r进度: {percent}%")
        sys.stdout.flush()
    
    urllib.request.urlretrieve(url, output_path, reporthook)
    print("\n下载完成!")

def main():
    # YOLOv8n PyTorch模型下载地址
    model_url = "https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt"
    
    # 下载目录
    download_dir = os.path.dirname(os.path.abspath(__file__))
    model_path = os.path.join(download_dir, "yolov8n.pt")
    
    # 下载PyTorch模型
    if not os.path.exists(model_path):
        print("步骤1: 下载YOLOv8n PyTorch模型...")
        download_file(model_url, model_path)
    else:
        print("PyTorch模型已存在，跳过下载")
    
    # 转换为TensorFlow Lite
    print("\n步骤2: 转换为TensorFlow Lite格式...")
    try:
        from ultralytics import YOLO
        
        # 加载模型
        model = YOLO(model_path)
        
        # 导出为TFLite格式
        print("正在导出为TFLite格式...")
        model.export(format="tflite", int8=True)
        
        # 查找生成的tflite文件
        tflite_files = []
        for root, dirs, files in os.walk(download_dir):
            for file in files:
                if file.endswith('.tflite'):
                    tflite_files.append(os.path.join(root, file))
        
        if tflite_files:
            # 选择float16版本（体积较小）
            target_file = None
            for f in tflite_files:
                if 'float16' in f:
                    target_file = f
                    break
            
            if not target_file:
                target_file = tflite_files[0]
            
            # 复制到assets目录
            assets_dir = os.path.join(download_dir, "app", "src", "main", "assets")
            os.makedirs(assets_dir, exist_ok=True)
            
            dest_path = os.path.join(assets_dir, "yolov8n.tflite")
            
            import shutil
            shutil.copy2(target_file, dest_path)
            
            print(f"\n成功! 模型已保存到: {dest_path}")
            print(f"文件大小: {os.path.getsize(dest_path) / 1024 / 1024:.2f} MB")
        else:
            print("错误: 未找到生成的TFLite文件")
            
    except ImportError:
        print("错误: 未安装ultralytics库")
        print("请先运行: pip install ultralytics")
        sys.exit(1)
    except Exception as e:
        print(f"转换失败: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
