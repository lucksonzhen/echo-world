$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

# Synthetic, fixed test phrases only. This script never opens a microphone.
$fixtureRoot = Join-Path $PSScriptRoot 'assets\setup-commands'
New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
$samples = [ordered]@{
 'next.wav' = '小助手下一步'
 'back.wav' = '小助手上一步'
 'provider.wav' = '小助手选择第二项'
 'help.wav' = '小助手再说一遍'
 'paste.wav' = '小助手粘贴密钥'
 'test.wav' = '小助手测试连接'
 'consent.wav' = '小助手同意上传'
 'confirm.wav' = '小助手确认操作'
 'cancel.wav' = '小助手取消操作'
 'skip.wav' = '小助手跳过'
 'bare-next.wav' = '下一步'
 'bare-provider.wav' = '选择第二项'
 'bare-confirm.wav' = '确认操作'
 'bare-cancel.wav' = '取消操作'
}
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synthesizer.SelectVoice('Microsoft Huihui Desktop')
$synthesizer.Rate = 0
$synthesizer.Volume = 100
$totalBytes = 0
try {
    foreach ($sample in $samples.GetEnumerator()) {
        $temporaryWave = Join-Path ([IO.Path]::GetTempPath()) ('tingjian-fixture-' + [guid]::NewGuid().ToString('N') + '.wav')
        try {
            $synthesizer.SetOutputToWaveFile($temporaryWave, $format)
            $synthesizer.Speak($sample.Value)
            $synthesizer.SetOutputToNull()
            $wave = [IO.File]::ReadAllBytes($temporaryWave)
            $dataOffset = -1
            $dataLength = 0
            for ($offset = 12; $offset + 8 -le $wave.Length;) {
                $chunkName = [Text.Encoding]::ASCII.GetString($wave, $offset, 4)
                $chunkLength = [BitConverter]::ToUInt32($wave, $offset + 4)
                if ($offset + 8L + $chunkLength -gt $wave.Length) { throw 'Truncated synthesized WAV.' }
                if ($chunkName -eq 'data') { $dataOffset = $offset + 8; $dataLength = $chunkLength; break }
                $offset += 8 + $chunkLength + ($chunkLength % 2)
            }
            if ($dataOffset -lt 0 -or $dataLength -eq 0 -or $dataLength % 2 -ne 0) { throw 'Missing PCM samples.' }
            # Add 350 ms leading and 1250 ms trailing silence for recognition endpointing.
            $leadingBytes = 11200
            $trailingBytes = 40000
            $pcmLength = $leadingBytes + $dataLength + $trailingBytes
            $stream = New-Object IO.MemoryStream
            $writer = New-Object IO.BinaryWriter($stream)
            try {
                $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF'))
                $writer.Write([int](36 + $pcmLength))
                $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
                $writer.Write([int]16)
                $writer.Write([int16]1) # PCM
                $writer.Write([int16]1) # mono
                $writer.Write([int]16000)
                $writer.Write([int]32000)
                $writer.Write([int16]2)
                $writer.Write([int16]16)
                $writer.Write([Text.Encoding]::ASCII.GetBytes('data'))
                $writer.Write([int]$pcmLength)
                $writer.Write((New-Object byte[] $leadingBytes))
                $writer.Write($wave, $dataOffset, $dataLength)
                $writer.Write((New-Object byte[] $trailingBytes))
                $writer.Flush()
                $output = Join-Path $fixtureRoot $sample.Key
                [IO.File]::WriteAllBytes($output, $stream.ToArray())
                $totalBytes += $stream.Length
                Write-Output ('{0}: {1} bytes, {2:N2} seconds' -f $sample.Key, $stream.Length, ($pcmLength / 32000.0))
            } finally { $writer.Dispose(); $stream.Dispose() }
        } finally {
            $synthesizer.SetOutputToNull()
            if (Test-Path -LiteralPath $temporaryWave) { Remove-Item -LiteralPath $temporaryWave }
        }
    }
} finally { $synthesizer.Dispose() }
if ($totalBytes -ge 2MB) { throw 'Synthetic fixtures exceed the 2 MiB budget.' }
Write-Output ('Total fixture size: ' + $totalBytes + ' bytes')
