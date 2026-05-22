const express = require("express");

const app = express();
const PORT = 3000;

app.get("/status", (_req, res) => {
  res.json({ status: "OK" });
});

app.listen(PORT, () => {
  console.log(`demo-node-service listening on port ${PORT}`);
});
