# setup_and_build.ps1
# Automates building llama.cpp with KleidiAI for Android (Snapdragon 8 Elite / Oryon CPU)
param (
    [string]$NdkPath = "C:\Users\rawat\AppData\Local\Android\Sdk\ndk\28.2.13676358",
    [switch]$EnableSME = $false,
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"
$WorkspaceDir = (Get-Item $PSScriptRoot).Parent.FullName
Set-Location $WorkspaceDir

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " llama.cpp + KleidiAI Builder for Android Snapdragon" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Check/Clone Repositories
if (-not (Test-Path "$WorkspaceDir\llama.cpp")) {
    Write-Host "[1/5] Cloning llama.cpp (ggml-org)..." -ForegroundColor Yellow
    git clone https://github.com/ggml-org/llama.cpp.git "$WorkspaceDir\llama.cpp"
    Set-Location "$WorkspaceDir\llama.cpp"
    git checkout 2e92ecd0247d25f09797f8fdb044a166522fc05d
} else {
    Write-Host "[1/5] llama.cpp directory exists." -ForegroundColor Green
    Set-Location "$WorkspaceDir\llama.cpp"
    git checkout 2e92ecd0247d25f09797f8fdb044a166522fc05d
}

if (-not (Test-Path "$WorkspaceDir\kleidiai")) {
    Write-Host "[2/5] Cloning Qualcomm KleidiAI (kleidi-ai-qmx)..." -ForegroundColor Yellow
    git clone --branch kleidi-ai-qmx https://github.com/qualcomm/kleidiai.git "$WorkspaceDir\kleidiai"
} else {
    Write-Host "[2/5] KleidiAI directory exists." -ForegroundColor Green
    Set-Location "$WorkspaceDir\kleidiai"
    git checkout kleidi-ai-qmx
}

# 2. Configure Arch Flags
$buildDir = "$WorkspaceDir\llama.cpp\build"
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Path $buildDir | Out-Null
Set-Location $buildDir

if ($EnableSME) {
    Write-Host "[3/5] Configuring CMake with ARM SME/SVE2 ISA flags (QMX-ready for Galaxy S26)..." -ForegroundColor Yellow
    $arch = "armv9.2-a+sve2+sme+dotprod+i8mm"
} else {
    Write-Host "[3/5] Configuring CMake with ARMv8.6-A + DotProd + I8MM flags (Galaxy S25 Ultra)..." -ForegroundColor Yellow
    $arch = "armv8.6-a+dotprod+i8mm+bf16"
}

cmake -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-29 `
  "-DCMAKE_TOOLCHAIN_FILE=$NdkPath\build\cmake\android.toolchain.cmake" `
  -DLLAMA_CURL=OFF `
  -DGGML_CPU_KLEIDIAI=ON `
  -DGGML_SYSTEM_ARCH=ARM `
  -DGGML_CPU_AARCH64=ON `
  "-DGGML_CPU_ARM_ARCH=$arch" `
  "-DCMAKE_C_FLAGS=-march=$arch -fno-omit-frame-pointer -g" `
  "-DCMAKE_CXX_FLAGS=-march=$arch -fno-omit-frame-pointer -g" `
  -DCMAKE_C_COMPILER_TARGET=aarch64-linux-android29 `
  -DCMAKE_CXX_COMPILER_TARGET=aarch64-linux-android29 `
  -DCMAKE_EXPORT_COMPILE_COMMANDS=ON `
  -DGGML_LLAMAFILE=OFF `
  -DLLAMA_BUILD_SERVER=OFF `
  ..

# 3. Build
Write-Host "[4/5] Building with Ninja..." -ForegroundColor Yellow
ninja -j16 llama-batched-bench llama-bench

# 4. Deploy to Device if ADB is connected
if (-not $DeviceId) {
    $devices = adb devices | Where-Object { $_ -match "\tdevice$" }
    if ($devices) {
        $DeviceId = ($devices[0] -split "\t")[0]
    }
}

if ($DeviceId) {
    Write-Host "[5/5] Deploying binaries to device $DeviceId..." -ForegroundColor Yellow
    $dest = "/data/local/tmp/qmx_bench"
    adb -s $DeviceId shell "mkdir -p $dest /data/local/tmp/models"
    
    $binDir = "$buildDir\bin"
    Get-ChildItem "$binDir\*.so" | ForEach-Object { adb -s $DeviceId push $_.FullName "$dest/$($_.Name)" }
    adb -s $DeviceId push "$binDir\llama-batched-bench" "$dest/llama-batched-bench"
    adb -s $DeviceId push "$binDir\llama-bench" "$dest/llama-bench"
    adb -s $DeviceId push "$NdkPath\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\19\lib\linux\aarch64\libomp.so" "$dest/libomp.so"
    adb -s $DeviceId shell "chmod 755 $dest/llama-batched-bench $dest/llama-bench"
    Write-Host "Deployment completed successfully!" -ForegroundColor Green
} else {
    Write-Host "[5/5] No ADB device connected. Binaries built in: $buildDir\bin" -ForegroundColor Cyan
}
