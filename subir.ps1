# Ejecutar DENTRO de la carpeta del proyecto (la que tiene src y config.properties).
# Antes: crear en GitHub el repositorio publico y vacio  AP-U1-Silva-John  (sin README ni .gitignore).

$remoto = "https://github.com/Johnst2000/AP-U1-Silva-John.git"

function Commit($rutas, $mensaje) {
    $existentes = @($rutas | Where-Object { Test-Path $_ })
    if ($existentes.Count -eq 0) { return }
    git add -- $existentes
    git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) { git commit -m $mensaje }
}

if (-not (Test-Path ".git")) { git init -b main }
git config user.name "John Jairo Silva Triviño"
git config user.email "jsilvat2@uteq.edu.ec"

Commit @("src/distribuidos", "config.properties") "Inicio AP-U1 - codigo de sesion: KD55-TB - configuracion, reloj de Lamport y registro de eventos"
Commit @("src/seguridad", "src/comunicacion") "Autenticacion por token y formato de mensajes TCP"
Commit @("src/Coordinador.java") "Coordinador: servidor TCP, contador con exclusion mutua y deteccion de latidos"
Commit @("src/Puerta.java") "Puerta: entradas, salidas, heartbeat y reloj de Lamport"
Commit @("src/PruebaCarga.java") "PruebaCarga: 40 entradas simultaneas, modo normal vs inseguro"
Commit @("src/OrdenTotal.java") "OrdenTotal: orden total de logs y verificacion de causalidad"
Commit @("compilar.sh", "compilar.bat", ".gitignore", "README.md", "logs", "docs") "Cierre AP-U1 - codigo de sesion: 662D-N6 - scripts de compilacion, README, logs y docs"

if (-not (git remote)) { git remote add origin $remoto }
git push -u origin main
git log --oneline
