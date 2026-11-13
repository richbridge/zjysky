/**
 * 阻断事件冒泡到 kdesign 组件自带的 onChange。
 *
 * 用途：仅用于 Tabs 的页签标题 / 首页图标。TabPane 的文字 <span> 上挂了
 * kdesign 自己的 onChange，若不阻断，React Router <Link> 的跳转会和它
 * 叠加成两次 history（后退要点两次才能回去）。
 * stopPropagation 只阻止冒泡，不会 preventDefault，Link 自身的路由跳转照常执行。
 *
 * 注意：菜单 NavLink 刻意不用它 —— Menu.SubMenu 的弹出层正是依赖点击冒泡
 * 到 document 才会关闭，阻断后弹出层会残留并覆盖菜单，导致下一次点击落空。
 */
export function stopBubble(event) {
  event?.stopPropagation();
}

/**
 * 菜单 NavLink 的点击处理：登记"这个页签已被打开"。
 *
 * 刻意不 stopPropagation：kdesign 的 SubMenu 弹出层靠点击冒泡到 document 才关闭。
 * 导航由 <NavLink> 自身独占（真实 href，支持新标签打开 / 修饰键）；
 * 冒泡到 Menu 的 onClick 只做幂等登记，因此不会产生双重导航。
 */
export function openTabOnClick(onOpen, path) {
  return () => {
    onOpen(path);
  };
}
