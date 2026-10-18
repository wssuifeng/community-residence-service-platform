<script setup lang="ts">
/**
 * 品牌 Logo：前排主屋（负形门）+ 后排小屋，几何扁平、单色 currentColor，
 * 随上下文变色（白底导航 / 沉浸式透明导航 / 深色页脚通用）
 */
defineProps<{
  size?: number
}>()
</script>

<template>
  <svg
    class="app-logo"
    :width="size ?? 22"
    :height="size ?? 22"
    viewBox="0 0 32 32"
    fill="currentColor"
    aria-hidden="true"
  >
    <!-- 后排小屋：45% 透明度退后，形成社区层次 -->
    <path d="M23.5 8 29.5 13.8h-2.6v7.4h-6.8v-7.4h-2.6L23.5 8z" opacity="0.45" />
    <!-- 前排主屋：evenodd 挖出门形负空间 -->
    <path
      fill-rule="evenodd"
      d="M13 4.5 2.5 13.5h2.8v13.2h15.4V13.5h2.8L13 4.5zM10.8 26.7v-7.4h4.4v7.4h-4.4z"
    />
  </svg>
</template>
