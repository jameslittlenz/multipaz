(function() {
    const form = document.getElementById("settingsForm");
    if (!form) {
        return;
    }
    const status = document.getElementById("settingsStatus");

    const FIELDS = [
        {
            key: "face_match_threshold", type: "number", step: "0.01", min: "0", max: "1",
            label: "Face match threshold (0-1)",
            hint: "Higher rejects more false accepts (impostors) but also more real matches " +
                "(false rejects). Lower is more permissive."
        },
        {
            key: "require_active_auth", type: "checkbox", label: "Require Active Authentication",
            hint: "Off by default: multipaz-idv's Active Authentication verifier hasn't been " +
                "built yet, so turning this on rejects every passport (fails closed)."
        },
        {
            key: "accept_untrusted_csca", type: "checkbox", label: "Accept untrusted CSCA (demo mode)",
            hint: "Accepts an otherwise fully-verified passport whose CSCA isn't in the trust " +
                "store. Only for demos with non-Validatopia test data; leave off for a real deployment."
        },
        {
            key: "offer_ttl_seconds", type: "number", step: "1", min: "1",
            label: "Credential offer TTL (seconds)",
            hint: "How long a generated offer stays redeemable."
        },
        {
            key: "photo_id_validity_days", type: "number", step: "1", min: "1",
            label: "Photo ID validity (days)",
            hint: "The minted credential's expiry is the earlier of this and the passport's own expiry."
        },
        {
            key: "data_retention_days", type: "number", step: "1", min: "0",
            label: "Passport data retention (days)",
            hint: "How long the encrypted SOD/DG1/DG2 and derived claims are kept for credential refresh."
        },
        {
            key: "dummy_issuance_enabled", type: "checkbox", label: "Dummy (persona) issuance enabled",
            hint: "Turns the \"Use a test identity\" wallet option, and /idv/personas, on or off."
        },
    ];

    const inputs = {};
    for (const field of FIELDS) {
        const row = document.createElement("div");
        const label = document.createElement("label");
        label.textContent = field.label + ":";
        label.setAttribute("for", "setting-" + field.key);
        const input = document.createElement("input");
        input.id = "setting-" + field.key;
        input.type = field.type;
        if (field.step) input.step = field.step;
        if (field.min !== undefined) input.min = field.min;
        if (field.max !== undefined) input.max = field.max;
        label.appendChild(input);
        row.appendChild(label);
        const hint = document.createElement("span");
        hint.className = "field_hint";
        hint.textContent = field.hint;
        row.appendChild(hint);
        form.appendChild(row);
        inputs[field.key] = input;
    }
    const saveButton = document.createElement("button");
    saveButton.type = "submit";
    saveButton.textContent = "Save settings";
    form.appendChild(saveButton);

    async function loadSettings() {
        const data = await (await window.adminFetch("admin_idv_settings")).json();
        for (const field of FIELDS) {
            if (field.type === "checkbox") {
                inputs[field.key].checked = !!data[field.key];
            } else {
                inputs[field.key].value = data[field.key];
            }
        }
    }

    form.addEventListener("submit", async function(e) {
        e.preventDefault();
        const body = {};
        for (const field of FIELDS) {
            body[field.key] = field.type === "checkbox"
                ? inputs[field.key].checked
                : Number(inputs[field.key].value);
        }
        const response = await window.adminFetch("admin_idv_settings", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body)
        });
        status.textContent = response.ok ? "Saved." : "Failed to save settings.";
        if (response.ok) {
            await loadSettings();
        }
    });

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", loadSettings);
    if (login.getAttribute("loggedIn") == "true") {
        loadSettings();
    }
})();
