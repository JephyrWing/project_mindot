from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from agent import agent

class AgentRequest(BaseModel):
    query: str


class AgentResponse(BaseModel):
    answer: str

app = FastAPI(
    title="",
    description="",
    version="1.0.0"
)


# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"]
)

@app.get("/")
def root():
    return {
        "message": "",
        "swagger": "/docs"
    }

@app.post(
    "/internal/ai/records",
    response_model=AgentResponse,
)
def run_agent(query: AgentRequest) -> AgentResponse:
  result = agent.invoke({
      "messages": [
          {
              "role": "user",
              "content": query.query
          }
      ]
  })
  return AgentResponse(answer=result["messages"][-1].content)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8000
    )