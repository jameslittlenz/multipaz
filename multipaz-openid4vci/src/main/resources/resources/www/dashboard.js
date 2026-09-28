(function() {
    const container = document.getElementById("dashboardSummary");
    if (!container) {
        return;
    }

    async function refreshDashboard() {
        const entries = await (await window.adminFetch("admin_idv_audit?limit=200")).json();
        container.innerHTML = "";
        if (entries.length === 0) {
            container.textContent = "No identity-verification attempts recorded yet.";
            return;
        }
        const byMethod = {};
        let accepted = 0;
        const flagCounts = {};
        for (const entry of entries) {
            byMethod[entry.method] = (byMethod[entry.method] || 0) + 1;
            if (entry.accepted) {
                accepted++;
            }
            for (const flag of entry.flags || []) {
                flagCounts[flag] = (flagCounts[flag] || 0) + 1;
            }
        }
        addStat(container, "Attempts (most recent " + entries.length + ")", entries.length);
        addStat(container, "Pass rate", Math.round((accepted / entries.length) * 100) + "%");
        for (const [method, count] of Object.entries(byMethod)) {
            addStat(container, "Method: " + method, count);
        }
        const flagEntries = Object.entries(flagCounts).sort((a, b) => b[1] - a[1]);
        if (flagEntries.length > 0) {
            const h4 = document.createElement("h4");
            h4.textContent = "Recent flags";
            container.appendChild(h4);
            const list = document.createElement("ul");
            for (const [flag, count] of flagEntries) {
                const li = document.createElement("li");
                li.textContent = flag + ": " + count;
                list.appendChild(li);
            }
            container.appendChild(list);
        }
    }

    function addStat(container, label, value) {
        const div = document.createElement("div");
        const labelSpan = document.createElement("span");
        labelSpan.className = "label";
        labelSpan.textContent = label + ": ";
        div.appendChild(labelSpan);
        const valueSpan = document.createElement("span");
        valueSpan.textContent = value;
        div.appendChild(valueSpan);
        container.appendChild(div);
    }

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", refreshDashboard);
    if (login.getAttribute("loggedIn") == "true") {
        refreshDashboard();
    }
})();
