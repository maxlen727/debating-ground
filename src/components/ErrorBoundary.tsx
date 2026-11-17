import { Component, type ReactNode } from 'react';
import { exportLibrary, exportSession } from '../lib/exporter.ts';
import { getState, update } from '../store.ts';

// 界面渲染出错时的最后一道防线：不留白屏，告诉学习者记录还在，并给出能走的路。
// 记录存在 localStorage 里，界面出错不会动它；这里的按钮都不依赖出错的那一页。
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  // 回到首页再试：多数出错只发生在某一页
  home = () => {
    update(s => { s.ui = { ...s.ui, screen: 'learn', sheet: null, explain: null, term: null, invite: false, openCard: null, busy: null }; });
    this.setState({ failed: false });
  };
  render() {
    if (!this.state.failed) return this.props.children;
    const { library, session } = getState();
    return (
      <div className="app">
        <div className="screen">
          <div className="empty" role="alert">
            <h2>这一页没能显示出来</h2>
            <p>你的知识库和讨论记录还保存在这台设备上，没有丢。先回到首页；如果还是打不开，把内容导出来，再重新打开思辨场。</p>
            <button className="primary" onClick={this.home}>回到首页</button>
            {library.length > 0 && <button className="ghost" onClick={() => exportLibrary(library)}>导出知识库</button>}
            {session && <button className="ghost" onClick={() => exportSession(session)}>导出这次讨论</button>}
            <button className="ghost" onClick={() => location.reload()}>重新打开</button>
          </div>
        </div>
      </div>
    );
  }
}
