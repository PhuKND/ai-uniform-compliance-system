@echo off
setlocal
cd /d "%~dp0.."
echo Starting integrated uniform-ai server...
echo Open http://127.0.0.1:5001/api/uniform/health in your browser.
python app.py
pause
