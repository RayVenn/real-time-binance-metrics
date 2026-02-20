from fastapi import FastAPI

from app.api.routes import router

app = FastAPI(
    title="Portfolio Optimizer API",
    description="Mean-Variance Portfolio Optimization Service",
    version="0.1.0",
)

app.include_router(router, prefix="/api/v1")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
