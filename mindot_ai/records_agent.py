from dotenv import load_dotenv
load_dotenv()
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain.agents import create_agent

# LLM 생성
llm = ChatOpenAI(model="gpt-4o-mini", temperature=0.0)
embeddings = OpenAIEmbeddings(model="text-embedding-3-small")