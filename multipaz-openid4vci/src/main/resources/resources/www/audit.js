(function() {
    const idvBody = document.querySelector("#idvAuditTable tbody");
    if (!idvBody) {
        return;
    }
    const actionBody = document.querySelector("#actionLogTable tbody");
    const basePath = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    document.getElementById("idvAuditCsvLink").href = basePath + "admin_idv_audit?format=csv&limit=1000";
    document.getElementById("actionLogCsvLink").href = basePath + "admin_action_log?format=csv&limit=1000";

    const formatter = new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "medium" });

    function addCell(row, text) {
        const cell = document.createElement("td");
        cell.textContent = text;
        row.appendChild(cell);
    }

    async function refreshIdvAudit() {
        const entries = await (await window.adminFetch("admin_idv_audit?limit=100")).json();
        idvBody.innerHTML = "";
        for (const entry of entries) {
            const row = document.createElement("tr");
            addCell(row, formatter.format(new Date(entry.timestamp * 1000)));
            addCell(row, entry.method);
            addCell(row, entry.accepted ? "Yes" : "No");
            addCell(row, entry.nationality || "");
            addCell(row, entry.masked_document_number || "");
            addCell(row, entry.face_score !== undefined ? entry.face_score.toFixed(2) : "");
            addCell(row, (entry.flags || []).join(", "));
            idvBody.appendChild(row);
        }
    }

    async function refreshActionLog() {
        const entries = await (await window.adminFetch("admin_action_log?limit=100")).json();
        actionBody.innerHTML = "";
        for (const entry of entries) {
            const row = document.createElement("tr");
            addCell(row, formatter.format(new Date(entry.timestamp * 1000)));
            addCell(row, entry.username || "");
            addCell(row, entry.action);
            addCell(row, entry.detail);
            actionBody.appendChild(row);
        }
    }

    async function refreshAudit() {
        await Promise.all([refreshIdvAudit(), refreshActionLog()]);
    }

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", refreshAudit);
    if (login.getAttribute("loggedIn") == "true") {
        refreshAudit();
    }
})();
