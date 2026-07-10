from PIL import Image
import torch
from transformers import AutoProcessor, AutoModelForCausalLM

model_id = "microsoft/Florence-2-base-ft"
device = "cuda:0" if torch.cuda.is_available() else "cpu"
torch_dtype = torch.float16 if torch.cuda.is_available() else torch.float32

print("Loading model...")
model = AutoModelForCausalLM.from_pretrained(
    model_id,
    torch_dtype=torch_dtype,
    trust_remote_code=True
).to(device)
model.eval()

print("Loading processor...")
processor = AutoProcessor.from_pretrained(
    model_id,
    trust_remote_code=True
)

print("Opening image...")
image = Image.open("test.jpg").convert("RGB")
prompt = "<MORE_DETAILED_CAPTION>"

print("Preparing inputs...")
inputs = processor(text=prompt, images=image, return_tensors="pt").to(device, torch_dtype)

print("Running inference...")
with torch.no_grad():
    generated_ids = model.generate(
        input_ids=inputs["input_ids"],
        pixel_values=inputs["pixel_values"],
        max_new_tokens=256,
        num_beams=3,
        do_sample=False
    )

generated_text = processor.batch_decode(generated_ids, skip_special_tokens=False)[0]
parsed_answer = processor.post_process_generation(
    generated_text,
    task=prompt,
    image_size=(image.width, image.height)
)

print("=== RAW TEXT ===")
print(generated_text)
print("=== PARSED RESULT ===")
print(parsed_answer)