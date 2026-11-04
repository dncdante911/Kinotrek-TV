@echo off
rem Opens the Kinotrek statistics dashboard. It listens only inside the server (127.0.0.1:8448), so this
rem window keeps an SSH tunnel to it; close the window to close the tunnel.
rem   kinostats.bat          - Oracle (cloud relay), with your Oracle key
rem   kinostats.bat radio    - the office server "radio" (asks for its password)
if /i "%1"=="radio" (
  start "" http://127.0.0.1:18448/
  ssh -N -L 18448:127.0.0.1:8448 -p 231 dncdante@46.232.232.38
) else (
  start "" http://127.0.0.1:18448/
  ssh -N -L 18448:127.0.0.1:8448 -i "%USERPROFILE%\.ssh\kinotrek_oracle" ubuntu@158.179.206.162
)
