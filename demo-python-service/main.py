from fastapi import FastAPI
from fastapi.responses import JSONResponse

app = FastAPI(title="demo-python-service")

@app.get("/health")
async def health():
    return JSONResponse(content={"status": "UP"})
