# run_benchmarks.ps1
# Automates running llama.cpp batched benchmarks across model and thread configs
param (
    [string]$DeviceId = "",
    [string]$ModelDir = "/data/local/tmp/models",
    [string]$BenchDir = "/data/local/tmp/qmx_bench"
)

if (-not $DeviceId) {
    $devices = adb devices | Where-Object { $_ -match "\tdevice$" }
    if ($devices) {
        $DeviceId = ($devices[0] -split "\t")[0]
    }
}

if (-not $DeviceId) {
    Write-Error "No ADB device connected!"
    exit 1
}

Write-Host "Running benchmarks on device: $DeviceId" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan

$models = @(
    "gemma-3-270m-it-Q4_0.gguf",
    "gemma-3-270m-it-Q8_0.gguf"
)

$threads = @(1, 4)

foreach ($model in $models) {
    foreach ($t in $threads) {
        Write-Host "`n>>> Running: $model (Threads: $t) <<<" -ForegroundColor Yellow
        adb -s $DeviceId shell "cd $BenchDir && LD_LIBRARY_PATH=. ./llama-batched-bench -m $ModelDir/$model -c 2048 -b 2048 -ub 512 -npp 128 -ntg 128 -npl 1 -t $t -fa on"
    }
}
