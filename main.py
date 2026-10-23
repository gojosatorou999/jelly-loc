"""
Jelly Loc — Local Development Server Runner.
Run with: python main.py
"""

import uvicorn

if __name__ == "__main__":
    print("🚀 Starting Jelly Loc Server on http://0.0.0.0:8000 ...")
    uvicorn.run("api.index:app", host="0.0.0.0", port=8000, reload=True)
