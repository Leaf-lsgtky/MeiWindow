# Regenerates PinyinTables.kt from the decompiled source. Run from repo root.
param([string]$Jadx = "E:\workspace\flyme\out\SystemUITools_src\sources\com\flyme\systemuitools\common\utils\C2282f.java")
$src = Get-Content $Jadx -Raw
$nums = [regex]::Matches([regex]::Match($src,'f7593b\s*=\s*\{([^}]*)\}').Groups[1].Value,'\d+') | ForEach-Object { $_.Value }
$rows = [regex]::Matches([regex]::Match($src,'f7594c\s*=\s*\{(.*?)\};','Singleline').Groups[1].Value,'new byte\[\]\{([^}]*)\}')
if ($nums.Count -ne $rows.Count) { throw "size mismatch" }
$py = foreach($r in $rows){ -join ([regex]::Matches($r.Groups[1].Value,'\d+') | ForEach-Object { [char]([int]$_.Value) } | Where-Object { $_ -ne [char]0 }) }
$lines1 = 0..($nums.Count-1) | ForEach-Object { "    '" + [char][int]$nums[$_] + "'" + $(if($_ -lt $nums.Count-1){","}else{""}) }
$lines2 = 0..($py.Count-1) | ForEach-Object { '    "' + $py[$_] + '"' + $(if($_ -lt $py.Count-1){","}else{""}) }
$text = "package com.repl.bubbledrawer.pinyin`n`n// Transcribed verbatim from decompiled C2282f (HanziToPinyin), $($nums.Count) entries.`n// Regenerate with this script. Do not hand-edit.`ninternal object PinyinTables {`n    val BOUNDARIES: CharArray = charArrayOf(`n" + ($lines1 -join "`n") + "`n    )`n`n    val PINYINS: Array<String> = arrayOf(`n" + ($lines2 -join "`n") + "`n    )`n}`n"
[System.IO.File]::WriteAllText("$PSScriptRoot\..\app\src\main\java\com\repl\bubbledrawer\pinyin\PinyinTables.kt", $text, [System.Text.UTF8Encoding]::new($false))
Write-Host "regenerated $($nums.Count) entries"
