# run_chat_app.ps1
# Interactive AI Chat directly on your mobile Snapdragon CPU with QMX / SME Hardware Acceleration
param (
    [string]$Prompt = "",
    [string]$Model = "gemma-3-270m-it-Q8_0.gguf",
    [int]$Threads = 4,
    [int]$MaxTokens = 256,
    [float]$Temp = 0.7,
    [string]$DeviceId = ""
)

if (-not $DeviceId) {
    $rawDevices = @((adb devices) -split "`r?`n" | Where-Object { $_ -match "^[a-zA-Z0-9_-]+\s+device" })
    if ($rawDevices.Count -gt 0) {
        $DeviceId = ($rawDevices[0].ToString().Trim() -split "\s+")[0]
    } else {
        Write-Error "No connected ADB device found."
        exit 1
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Running QMX-Accelerated AI Chat on Device: $DeviceId" -ForegroundColor Green
Write-Host " Model: $Model | Threads: $Threads | Max Tokens: $MaxTokens" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$remoteDir = "/data/local/tmp/qmx_bench"
$modelPath = "/data/local/tmp/models/$Model"

if ($Prompt) {
    # Single prompt execution
    $cmd = "cd $remoteDir && LD_LIBRARY_PATH=. GGML_KLEIDIAI_SME=1 ./llama-completion -m $modelPath -p '$Prompt' -n $MaxTokens -t $Threads --temp $Temp -no-cnv --simple-io"
    adb -s $DeviceId shell "$cmd"
} else {
    # Interactive chat session
    Write-Host "Starting interactive chat session (type your messages, Ctrl+C to exit)...`n" -ForegroundColor Yellow
    $cmd = "cd $remoteDir && LD_LIBRARY_PATH=. GGML_KLEIDIAI_SME=1 ./llama-completion -m $modelPath -sys 'You are a helpful and concise AI assistant.' -n $MaxTokens -t $Threads --temp $Temp -cnv --simple-io"
    adb -s $DeviceId shell "$cmd"
}
