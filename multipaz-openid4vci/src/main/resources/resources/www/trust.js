(function() {
    const tableBody = document.querySelector("#trustTable tbody");
    if (!tableBody) {
        return;
    }
    const status = document.getElementById("trustStatus");
    const pemInput = document.getElementById("trustPemInput");
    const uploadButton = document.getElementById("trustUploadButton");
    const downloadLink = document.getElementById("downloadTestCsca");
    const basePath = location.pathname.substring(0, location.pathname.lastIndexOf("/") + 1);
    downloadLink.href = basePath + "admin_trust_test_csca";

    const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

    function renderList(list) {
        tableBody.innerHTML = "";
        for (const info of list) {
            const row = document.createElement("tr");
            addCell(row, info.subject);
            addCell(row, dateFormatter.format(new Date(info.not_before * 1000)));
            addCell(row, dateFormatter.format(new Date(info.not_after * 1000)));
            addCell(row, info.fingerprint);
            const actionCell = document.createElement("td");
            if (!info.built_in) {
                const deleteButton = document.createElement("button");
                deleteButton.textContent = "Delete";
                deleteButton.addEventListener("click", async function() {
                    if (!confirm("Delete this CSCA certificate?")) return;
                    const response = await window.adminFetch("admin_trust_delete", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ fingerprint: info.fingerprint })
                    });
                    renderList(await response.json());
                });
                actionCell.appendChild(deleteButton);
            } else {
                actionCell.textContent = "(built-in)";
            }
            row.appendChild(actionCell);
            tableBody.appendChild(row);
        }
    }

    function addCell(row, text) {
        const cell = document.createElement("td");
        cell.textContent = text;
        row.appendChild(cell);
    }

    async function refreshTrust() {
        renderList(await (await window.adminFetch("admin_trust")).json());
    }

    uploadButton.addEventListener("click", async function() {
        status.textContent = "";
        const response = await window.adminFetch("admin_trust", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ pem: pemInput.value })
        });
        if (response.ok) {
            pemInput.value = "";
            status.textContent = "Uploaded.";
            renderList(await response.json());
        } else {
            const body = await response.json().catch(() => ({}));
            status.textContent = "Error: " + (body.error_description || "upload failed");
        }
    });

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", refreshTrust);
    if (login.getAttribute("loggedIn") == "true") {
        refreshTrust();
    }
})();
