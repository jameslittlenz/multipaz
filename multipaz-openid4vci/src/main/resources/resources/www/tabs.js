(function() {
    const tabs = document.querySelectorAll('nav.admin_tabs [role="tab"]');
    if (tabs.length === 0) {
        return;
    }

    function selectTab(tab) {
        for (const t of tabs) {
            const selected = t === tab;
            t.setAttribute("aria-selected", selected ? "true" : "false");
            const panel = document.getElementById(t.getAttribute("aria-controls"));
            if (panel) {
                panel.hidden = !selected;
            }
        }
        if (location.hash !== "#" + tab.dataset.panel) {
            history.replaceState(null, "", "#" + tab.dataset.panel);
        }
        tab.dispatchEvent(new CustomEvent("adminTabShown", { bubbles: true }));
    }

    for (const tab of tabs) {
        tab.addEventListener("click", () => selectTab(tab));
    }

    const initial = Array.from(tabs).find((t) => "#" + t.dataset.panel === location.hash) || tabs[0];
    selectTab(initial);
})();
