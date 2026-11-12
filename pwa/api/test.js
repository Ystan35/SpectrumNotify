// Vercel function: POST { endpoint } -> one test push to that device
module.exports = async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
  const r = await require("../server/default.js").test(req.body);
  res.status(r.status).json(r.body);
};
