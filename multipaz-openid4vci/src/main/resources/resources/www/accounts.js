(function() {
    const tableBody = document.querySelector("#accountsTable tbody");
    if (!tableBody) {
        return;
    }
    const status = document.getElementById("accountsStatus");
    const usernameInput = document.getElementById("newAccountUsername");
    const passwordInput = document.getElementById("newAccountPassword");
    const addButton = document.getElementById("addAccountButton");

    const formatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

    function addCell(row, text) {
        const cell = document.createElement("td");
        cell.textContent = text;
        row.appendChild(cell);
    }

    function showEnrollment(enrollment) {
        status.innerHTML = "";
        const p = document.createElement("p");
        p.textContent = "TOTP secret for '" + enrollment.username + "' (share this with them once, " +
            "it won't be shown again): ";
        const code = document.createElement("code");
        code.textContent = enrollment.secret;
        p.appendChild(code);
        status.appendChild(p);
    }

    async function refreshAccounts() {
        const accounts = await (await window.adminFetch("admin_accounts")).json();
        tableBody.innerHTML = "";
        for (const account of accounts) {
            const row = document.createElement("tr");
            addCell(row, account.username);
            addCell(row, formatter.format(new Date(account.created_at)));
            addCell(row, account.totp_confirmed ? "Yes" : "No");
            addCell(row, account.locked ? "Yes" : "No");
            const actionCell = document.createElement("td");
            const resetButton = document.createElement("button");
            resetButton.textContent = "Reset TOTP";
            resetButton.addEventListener("click", async function() {
                if (!confirm("Reset TOTP for '" + account.username + "'? They'll need to re-enroll " +
                    "on their next login, and their existing sessions are revoked immediately.")) {
                    return;
                }
                const response = await window.adminFetch("admin_accounts_reset_totp", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ username: account.username })
                });
                if (response.ok) {
                    showEnrollment(await response.json());
                    refreshAccounts();
                }
            });
            actionCell.appendChild(resetButton);
            const deleteButton = document.createElement("button");
            deleteButton.textContent = "Remove";
            deleteButton.addEventListener("click", async function() {
                if (!confirm("Remove admin account '" + account.username + "'?")) {
                    return;
                }
                const response = await window.adminFetch("admin_accounts_delete", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ username: account.username })
                });
                if (response.ok) {
                    refreshAccounts();
                } else {
                    const body = await response.json().catch(() => ({}));
                    status.textContent = "Error: " + (body.error_description || "could not remove account");
                }
            });
            actionCell.appendChild(deleteButton);
            row.appendChild(actionCell);
            tableBody.appendChild(row);
        }
    }

    addButton.addEventListener("click", async function() {
        const response = await window.adminFetch("admin_accounts", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username: usernameInput.value, password: passwordInput.value })
        });
        if (response.ok) {
            usernameInput.value = "";
            passwordInput.value = "";
            showEnrollment(await response.json());
            refreshAccounts();
        } else {
            const body = await response.json().catch(() => ({}));
            status.textContent = "Error: " + (body.error_description || "could not add account");
        }
    });

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", refreshAccounts);
    if (login.getAttribute("loggedIn") == "true") {
        refreshAccounts();
    }
})();
