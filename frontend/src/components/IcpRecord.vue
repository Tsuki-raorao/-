<template>
  <span class="filing-records" aria-label="网站备案信息">
    <a class="icp-record" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">闽ICP备2026040659号</a>
    <span class="filing-separator" aria-hidden="true"> · </span>
    <a class="icp-record police-record" href="https://beian.mps.gov.cn/#/query/webSearch?domain=wx-s.cn" target="_blank" rel="noopener noreferrer">闽公网安备35082402000177号</a>
  </span>
</template>

<style scoped>
.filing-records { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.icp-record { color: inherit; font-size: 12px; line-height: 1.8; text-decoration: none; white-space: nowrap; }
.icp-record:hover { text-decoration: underline; }
.icp-record:focus-visible { outline: 2px solid currentColor; outline-offset: 4px; }
.filing-separator { opacity: .62; }
</style>
