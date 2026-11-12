// Vercel function: POST { subscription, picks, classes, lead, morning }
module.exports = async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  const r = await require("../server/default.js").subscribe(req.body);
  res.status(r.status).json(r.body);
};
