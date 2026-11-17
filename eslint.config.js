// 代码检查：只开“正确性”规则——抓类型检查抓不到的 bug（没处理的 Promise、Hook 漏写依赖、switch 漏分支等），
// 不管代码长什么样（格式属于风格选择，另行决定）。见 AGENTS.md 的命令一节。
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';

export default tseslint.config(
  {
    ignores: [
      'dist/',
      'docs/.vitepress/dist/',
      'docs/.vitepress/cache/',
      'android/',
      'native/',
      'worker/src/vendor/',
      '**/node_modules/',
      '.worker-dist/',
      'eval-out/',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // 学习流程里很多操作是“发出去就不管”的（比如按钮点击触发 AI 请求）：允许显式写 void
      // node:test 的 test()/describe() 返回 Promise 是正常写法，测试运行器会等它
      '@typescript-eslint/no-floating-promises': [
        'error',
        {
          ignoreVoid: true,
          allowForKnownSafeCalls: [
            {
              from: 'package',
              package: 'node:test',
              name: ['test', 'describe', 'it', 'before', 'after', 'beforeEach', 'afterEach'],
            },
          ],
        },
      ],
      // React 的 onClick={async…}：actions 里已经统一捕获错误（lib/ai.ts 的 task），这里不必再包一层
      '@typescript-eslint/no-misused-promises': [
        'error',
        { checksVoidReturn: { attributes: false } },
      ],
      // async 函数里没有 await 不是 bug（常见于实现一个返回 Promise 的接口）
      '@typescript-eslint/require-await': 'off',
      '@typescript-eslint/switch-exhaustiveness-check': 'error',
      // 下划线开头表示“故意不用”（比如占位的参数、解构时丢掉的字段）
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
          caughtErrors: 'none',
          ignoreRestSiblings: true,
        },
      ],
    },
  },
  // 测试里大量解析响应 JSON 再断言字段，逐个收窄类型只会让测试更难读
  {
    files: ['tests/**/*.ts'],
    rules: {
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      // 模拟 fetch、WebSocket、失败时的常见写法：把请求体当字符串读、保存 this、用任意值 reject
      '@typescript-eslint/no-base-to-string': 'off',
      '@typescript-eslint/no-this-alias': 'off',
      '@typescript-eslint/unbound-method': 'off',
      '@typescript-eslint/prefer-promise-reject-errors': 'off',
      '@typescript-eslint/restrict-plus-operands': 'off',
    },
  },
  {
    files: ['src/**/*.tsx', 'src/**/*.ts'],
    plugins: { 'react-hooks': reactHooks },
    // 只开 Hooks 的两条核心规则。插件里其余规则（purity、refs 等）是给 React Compiler 用的，本项目没用编译器
    rules: { 'react-hooks/rules-of-hooks': 'error', 'react-hooks/exhaustive-deps': 'error' },
  },
  // 配置文件、脚本不在 tsconfig 里：不做类型感知检查
  {
    files: ['eslint.config.js', '**/*.js', '**/*.mjs', 'docs/.vitepress/**/*.ts'],
    ...tseslint.configs.disableTypeChecked,
  },
);
