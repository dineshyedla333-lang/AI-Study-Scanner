# AI Study Scanner

## IMPORTANT: Required Python Environment (Windows)

For this project, ALWAYS run **all** Python commands (install/run/tests) using this exact virtual environment:

- Conda env name: `ai_study_scanner`
- Python path: `C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe`

Do **not** use the `base` environment for this app.

### Install backend dependencies
```bat
cmd /c ""C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m pip install -r ai-study-scanner\backend\requirements.txt"
```

### Run backend API (FastAPI)
```bat
cmd /c ""C:\Users\dines\anaconda3\envs\ai_study_scanner\python.exe" -m uvicorn app.main:app --app-dir ai-study-scanner --host 0.0.0.0 --port 8000 --log-level info"
```

### Health check
Open:
- http://127.0.0.1:8000/health

### Solve endpoint
`POST /solve` JSON body:
```json
{
  "question_text": "Solve: 2x+3=11",
  "exam_mode": true
}
