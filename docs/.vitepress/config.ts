import { defineConfig } from 'vitepress';

const REPO = 'https://github.com/maxlen727/debating-ground';
// GitHub Pages 上的地址：文档在 /debating-ground/，演示版界面在 /debating-ground/app/
const APP = 'https://maxlen727.github.io/debating-ground/app/';

export default defineConfig({
  lang: 'zh-CN',
  title: '思辨场',
  description: '通过和 AI 讨论、追问、辩论，把一个概念真正弄懂，并把理解落盘、定期回顾。',
  base: '/debating-ground/',
  cleanUrls: true,
  lastUpdated: true,
  head: [
    ['link', { rel: 'icon', href: "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%231F3B36'/%3E%3Ctext x='16' y='23' font-size='20' text-anchor='middle' fill='%23E8EFD9' font-family='serif'%3E%E3%80%9E%3C/text%3E%3C/svg%3E" }],
    ['meta', { name: 'theme-color', content: '#1F3B36' }]
  ],
  themeConfig: {
    nav: [
      { text: '使用指南', link: '/guide/what-is', activeMatch: '/guide/' },
      { text: '产品', link: '/product/principles', activeMatch: '/product/' },
      { text: '开发', link: '/dev/', activeMatch: '/dev/' },
      { text: '在线体验', link: APP }
    ],
    sidebar: {
      '/guide/': [
        { text: '开始', items: [
          { text: '思辨场是什么', link: '/guide/what-is' },
          { text: '快速开始', link: '/guide/getting-started' }
        ] },
        { text: '使用', items: [
          { text: '一次学习的流程', link: '/guide/learning-flow' },
          { text: '四种讨论方式', link: '/guide/modes' },
          { text: '复盘与知识库', link: '/guide/review' }
        ] },
        { text: '连接 AI', items: [
          { text: '密钥与隐私', link: '/guide/api-and-privacy' },
          { text: '常见问题', link: '/guide/faq' }
        ] }
      ],
      '/product/': [
        { text: '产品', items: [
          { text: '产品原则', link: '/product/principles' },
          { text: '路线图', link: '/product/roadmap' }
        ] }
      ],
      '/dev/': [
        { text: '开发', items: [
          { text: '开发指南', link: '/dev/' },
          { text: '架构', link: '/dev/architecture' },
          { text: '协作与 Agent 规范', link: '/dev/collaboration' }
        ] }
      ]
    },
    socialLinks: [{ icon: 'github', link: REPO }],
    editLink: { pattern: `${REPO}/edit/main/docs/:path`, text: '在 GitHub 上编辑此页' },
    search: { provider: 'local', options: { translations: { button: { buttonText: '搜索文档' }, modal: { noResultsText: '没有找到相关结果', footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' } } } } },
    outline: { label: '本页内容', level: [2, 3] },
    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdated: { text: '最后更新' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '目录',
    darkModeSwitchLabel: '外观',
    lightModeSwitchTitle: '切换到浅色',
    darkModeSwitchTitle: '切换到深色',
    footer: { message: '思辨场 · 讨论、落盘、回顾', copyright: '由一群学生开发' }
  }
});
