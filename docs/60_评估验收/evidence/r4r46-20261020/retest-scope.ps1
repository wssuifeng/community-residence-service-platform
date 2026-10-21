$ErrorActionPreference = 'Stop'
$baseUrl = if ($env:CRSP_BASE_URL) { $env:CRSP_BASE_URL.TrimEnd('/') } else { 'http://127.0.0.1:8087' }
$staffId = 4L
$outputPath = Join-Path $PSScriptRoot 'scope-retest.json'
if (-not $env:CRSP_ADMIN_PASSWORD -or -not $env:CRSP_SUPERADMIN_PASSWORD) { throw 'Set isolated database passwords in CRSP_ADMIN_PASSWORD and CRSP_SUPERADMIN_PASSWORD.' }

function Invoke-Api {
    param([string] $Method, [string] $Path, [string] $Token, [object] $Body)
    $params = @{ Method = $Method; Uri = "$baseUrl$Path"; Headers = @{ Authorization = "Bearer $Token" }; TimeoutSec = 15 }
    if ($PSBoundParameters.ContainsKey('Body')) { $params.ContentType = 'application/json'; $params.Body = ConvertTo-Json -InputObject $Body -Depth 8 -Compress }
    Invoke-RestMethod @params
}
function Get-Token {
    param([string] $Username, [string] $Password)
    $result = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/auth/admin/login" -ContentType 'application/json' -TimeoutSec 15 -Body (ConvertTo-Json -InputObject @{ username = $Username; password = $Password } -Compress)
    if ($result.code -ne 200 -or -not $result.data.token) { throw "Login failed for $Username (code $($result.code))." }
    $result.data.token
}
function Get-SortedIds { param($Values) @($Values | ForEach-Object { [long] $_ } | Sort-Object -Unique) }
function Flatten-Categories {
    param($Nodes)
    foreach ($node in @($Nodes)) { if ($null -ne $node) { $node; if ($node.children) { Flatten-Categories $node.children } } }
}
function Assert-EqualIds {
    param([string] $Name, $Actual, $Expected)
    $a = @(Get-SortedIds $Actual); $e = @(Get-SortedIds $Expected)
    if (($a -join ',') -ne ($e -join ',')) { throw "$Name mismatch: actual=[$($a -join ',')] expected=[$($e -join ',')]." }
}

$adminToken = Get-Token 'admin1' $env:CRSP_ADMIN_PASSWORD
$superToken = Get-Token 'superadmin' $env:CRSP_SUPERADMIN_PASSWORD
$before = (Invoke-Api GET "/api/v1/staff-capabilities/$staffId" $superToken).data
if ($before.staffId -ne $staffId) { throw 'Expected isolated test staff account 4.' }
Assert-EqualIds 'Initial communities' $before.communityIds @(1, 2)
$originalCommunities = @(Get-SortedIds $before.communityIds)
$originalCategories = @(Get-SortedIds $before.categoryIds)
$categories = Invoke-Api GET '/api/v1/communities/2/service-categories/all' $superToken
$testCategory = @(Flatten-Categories $categories.data) | Where-Object { $_.id -in @(8..14) } | Select-Object -First 1
if (-not $testCategory) { throw 'No seeded service category in community 2 (expected ID 8..14).' }

$mutated = $false; $resetSucceeded = $false
try {
    $null = Invoke-Api PUT "/api/v1/staff-capabilities/$staffId" $superToken @{ communityIds = $originalCommunities; categoryIds = @($testCategory.id) }
    $mutated = $true
    $adminBefore = (Invoke-Api GET "/api/v1/staff-capabilities/$staffId" $adminToken).data
    Assert-EqualIds 'Admin-visible communities' $adminBefore.communityIds @(1)
    if (@(Get-SortedIds $adminBefore.categoryIds) -contains [long] $testCategory.id) { throw 'Admin detail exposed a category bound only to community 2.' }
    $save = Invoke-Api PUT "/api/v1/staff-capabilities/$staffId" $adminToken @{ communityIds = @(1); categoryIds = @(Get-SortedIds $adminBefore.categoryIds) }
    Assert-EqualIds 'Admin-save communities' $save.data.communityIds @(1)
    $afterAdminSave = (Invoke-Api GET "/api/v1/staff-capabilities/$staffId" $superToken).data
    Assert-EqualIds 'Communities after admin save' $afterAdminSave.communityIds $originalCommunities
    if (@(Get-SortedIds $afterAdminSave.categoryIds) -notcontains [long] $testCategory.id) { throw 'Community 2 category binding was removed by community 1 admin save.' }
}
finally {
    if ($mutated) {
        $null = Invoke-Api PUT "/api/v1/staff-capabilities/$staffId" $superToken @{ communityIds = $originalCommunities; categoryIds = $originalCategories }
        $restored = (Invoke-Api GET "/api/v1/staff-capabilities/$staffId" $superToken).data
        Assert-EqualIds 'Restored communities' $restored.communityIds $originalCommunities
        Assert-EqualIds 'Restored categories' $restored.categoryIds $originalCategories
        $resetSucceeded = $true
    }
}

$evidence = [ordered]@{
    verifiedAt = (Get-Date).ToString('o'); target = $baseUrl; scope = 'isolated service only; no normal database writes'; staffId = $staffId
    baselineCommunities = $originalCommunities; baselineCategoryIds = $originalCategories
    testCategory = [ordered]@{ id = [long] $testCategory.id; name = $testCategory.name; communityId = 2 }
    adminDetail = [ordered]@{ code = 200; communityIds = @(1); communityTwoCategoryVisible = $false }
    adminSave = [ordered]@{ code = 200; ownCommunityIds = @(1) }
    superAdminAfterSave = [ordered]@{ code = 200; communitiesPreserved = $true; externalCategoryPreserved = $true; communityIds = @(Get-SortedIds $afterAdminSave.communityIds); categoryIds = @(Get-SortedIds $afterAdminSave.categoryIds) }
    testDataRestored = $resetSucceeded; finalCommunities = @(Get-SortedIds $restored.communityIds); finalCategoryIds = @(Get-SortedIds $restored.categoryIds)
}
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Output "PASS: cross-community category retained; isolated staff account restored; evidence=$outputPath"
