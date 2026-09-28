(function() {
    const list = document.getElementById("personaList");
    if (!list) {
        return;
    }
    const status = document.getElementById("personasStatus");
    const jsonInput = document.getElementById("personasJsonInput");
    const portraitsInput = document.getElementById("personaPortraitsInput");
    const uploadButton = document.getElementById("personasUploadButton");

    function renderPersonas(personas) {
        list.innerHTML = "";
        if (personas.length === 0) {
            list.textContent = "No personas configured.";
            return;
        }
        const ul = document.createElement("ul");
        for (const persona of personas) {
            const li = document.createElement("li");
            li.textContent = persona.id + ": " + persona.given_name + " " + persona.family_name;
            ul.appendChild(li);
        }
        list.appendChild(ul);
    }

    async function refreshPersonas() {
        renderPersonas(await (await window.adminFetch("admin_personas")).json());
    }

    function fileToBase64(file) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result.substring(reader.result.indexOf(",") + 1));
            reader.onerror = reject;
            reader.readAsDataURL(file);
        });
    }

    uploadButton.addEventListener("click", async function() {
        status.textContent = "Uploading...";
        const portraits = {};
        for (const file of portraitsInput.files) {
            portraits[file.name] = await fileToBase64(file);
        }
        const response = await window.adminFetch("admin_personas", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ personas_json: jsonInput.value, portraits })
        });
        if (response.ok) {
            status.textContent = "Uploaded.";
            renderPersonas(await response.json());
        } else {
            const body = await response.json().catch(() => ({}));
            status.textContent = "Error: " + (body.error_description || "upload failed");
        }
    });

    let login = document.getElementById("login");
    login.addEventListener("loggedIn", refreshPersonas);
    if (login.getAttribute("loggedIn") == "true") {
        refreshPersonas();
    }
})();
