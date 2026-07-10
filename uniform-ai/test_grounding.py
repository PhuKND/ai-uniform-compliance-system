from PIL import Image
import torch
from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection

model_id = "IDEA-Research/grounding-dino-tiny"
device = "cuda" if torch.cuda.is_available() else "cpu"

print("Loading processor...")
processor = AutoProcessor.from_pretrained(model_id)

print("Loading model...")
model = AutoModelForZeroShotObjectDetection.from_pretrained(model_id).to(device)
model.eval()

print("Opening image...")
image = Image.open("test.jpg").convert("RGB")

# Nên truyền dưới dạng list các label
text_labels = [["student", "white school shirt", "blue youth union shirt", "long black trousers", "black trousers", "red scarf"]]

inputs = processor(
    images=image,
    text=text_labels,
    return_tensors="pt"
).to(device)

print("Running inference...")
with torch.no_grad():
    outputs = model(**inputs)

results = processor.post_process_grounded_object_detection(
    outputs,
    inputs.input_ids,
    threshold=0.25,
    text_threshold=0.25,
    target_sizes=[image.size[::-1]]
)

print("=== RESULT ===")
result = results[0]
print(result)

# In đẹp hơn
for box, score, label in zip(result["boxes"], result["scores"], result["labels"]):
    box = [round(x, 2) for x in box.tolist()]
    print(f"Detected {label} | score={round(score.item(), 3)} | box={box}")
